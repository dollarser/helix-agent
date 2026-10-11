package com.helix.extensions.skills

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

data class SkillKey(
    val source: SkillSource,
    val name: String,
    val snapshotHash: String,
)

data class SkillListItem(
    val key: SkillKey,
    val description: String,
    val enabled: Boolean,
    val invocation: SkillInvocationPolicy = SkillInvocationPolicy(),
)

data class SkillResource(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val encoding: String,
    val content: String,
)

enum class SkillEnablementScope {
    SESSION,
    GLOBAL,
}

@Suppress("TooManyFunctions", "LongParameterList") // Narrow content/source/accepted-input ports; no execution owner.
class SkillRepository(
    private val snapshotsRoot: Path,
    private val stateFile: Path,
    private val trashRoot: Path,
    builtIns: List<SkillDocument> = BuiltInSkills.documents(),
    private val resourceLimitBytes: Long = 512 * 1024,
    private val sourceAvailable: (SkillKey, String?) -> Boolean = { _, _ -> true },
    private val onIndependentInstall: (SkillKey) -> Unit = {},
    private val beforeRemove: (SkillKey) -> Unit = {},
    private val contributedDocuments: (String?) -> List<SkillDocument> = { emptyList() },
    private val userActivations: (String, String) -> List<UserSkillActivation> = { _, _ -> emptyList() },
) {
    private val lock = Any()
    private val inspector = SkillSnapshotInspector(SkillImportLimits(), SkillLoader())
    private val records = linkedMapOf<SkillKey, SkillRecord>()
    private val globalOverrides = linkedMapOf<SkillKey, Boolean>()
    private val sessionOverrides = linkedMapOf<String, MutableMap<SkillKey, Boolean>>()
    private var contributionKeys = emptySet<SkillKey>()

    init {
        Files.createDirectories(snapshotsRoot)
        Files.createDirectories(trashRoot)
        requireSafeRoot(snapshotsRoot, "snapshots")
        requireSafeRoot(trashRoot, "trash")
        builtIns.forEach { document ->
            val key = document.catalogEntry.toKey()
            records[key] = SkillRecord(document, null)
        }
        scanSnapshots()
        loadState()
    }

    fun list(sessionId: String? = null): List<SkillListItem> =
        synchronized(lock) {
            refreshContributions()
            records.entries
                .filter { (key, _) -> sourceAvailable(key, sessionId) }
                .map { (key, record) ->
                    SkillListItem(
                        key,
                        record.document.catalogEntry.description,
                        isEnabled(key, sessionId),
                        record.document.invocation,
                    )
                }.sortedWith(compareBy({ it.key.name }, { it.key.source.name }, { it.key.snapshotHash }))
        }

    /** Manual management preview only; execution must use read and its enablement checks. */
    fun inspect(key: SkillKey): SkillDocument =
        synchronized(lock) {
            refreshContributions()
            requireNotNull(records[key]) { "Unknown skill snapshot: ${key.name}" }.document
        }

    fun read(
        key: SkillKey,
        sessionId: String? = null,
        turnId: String? = null,
    ): SkillDocument {
        val activations = acceptedActivations(sessionId, turnId)
        return synchronized(lock) {
            refreshContributions()
            val document = requireNotNull(records[key]) { "Unknown skill snapshot: ${key.name}" }.document
            require(isEnabled(key, sessionId)) { "Skill is disabled: ${key.name}" }
            requireModelEligibility(document, key, activations)
            document
        }
    }

    fun readResource(
        key: SkillKey,
        relativePath: String,
        sessionId: String? = null,
        turnId: String? = null,
    ): SkillResource {
        val activations = acceptedActivations(sessionId, turnId)
        return synchronized(lock) {
            refreshContributions()
            val record = records[key] ?: throw IllegalArgumentException("Unknown skill snapshot: ${key.name}")
            require(isEnabled(key, sessionId)) { "Skill is disabled: ${key.name}" }
            requireModelEligibility(record.document, key, activations)
            skillResourceParts(relativePath)
            val bytes =
                if (record.directory == null) {
                    val text = requireNotNull(record.document.resources[relativePath]) { "Unknown skill resource" }
                    text.toByteArray(Charsets.UTF_8).also {
                        require(it.size <= resourceLimitBytes) { "Skill resource exceeds the read limit" }
                    }
                } else {
                    readBounded(resourcePath(record.directory, relativePath))
                }
            val text = decodeUtf8OrNull(bytes)
            SkillResource(
                relativePath = relativePath,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
                encoding = if (text == null) "base64" else "utf-8",
                content = text ?: Base64.getEncoder().encodeToString(bytes),
            )
        }
    }

    fun listForModel(
        sessionId: String?,
        turnId: String?,
    ): List<SkillListItem> {
        val activations = acceptedActivations(sessionId, turnId)
        return synchronized(lock) {
            list(sessionId).filter { item ->
                modelEligible(records.getValue(item.key).document, item.key, activations)
            }
        }
    }

    /** Validate a real UI choice before acceptance; a selected plugin alone is not invocation. */
    fun prepareUserActivation(
        key: SkillKey,
        sessionId: String,
        arguments: String,
    ): UserSkillActivation =
        synchronized(lock) {
            refreshContributions()
            require(arguments.length <= 4096 && '\u0000' !in arguments) { "Skill arguments exceed bounds" }
            require(isEnabled(key, sessionId)) { "Skill is disabled: ${key.name}" }
            val document = requireNotNull(records[key]) { "Unknown skill snapshot: ${key.name}" }.document
            require(document.invocation.userInvocable) { "SKILL_USER_INVOCATION_DISABLED" }
            require(document.invocation.unsupportedExecutionFields.isEmpty()) { "SKILL_EXECUTION_UNSUPPORTED" }
            UserSkillActivation(key, arguments)
        }

    fun activatedContent(
        sessionId: String,
        turnId: String,
    ): List<Pair<SkillDocument, String>> =
        userActivations(sessionId, turnId).map { activation ->
            read(activation.key, sessionId, turnId) to activation.arguments
        }

    /** Durable lookup must not acquire a Room transaction while holding the content lock. */
    private fun acceptedActivations(
        sessionId: String?,
        turnId: String?,
    ): List<UserSkillActivation> =
        if (sessionId == null || turnId == null) emptyList() else userActivations(sessionId, turnId)

    private fun requireModelEligibility(
        document: SkillDocument,
        key: SkillKey,
        activations: List<UserSkillActivation>,
    ) {
        require(document.invocation.unsupportedExecutionFields.isEmpty()) { "SKILL_EXECUTION_UNSUPPORTED" }
        require(modelEligible(document, key, activations)) { "SKILL_MODEL_INVOCATION_DISABLED" }
    }

    private fun modelEligible(
        document: SkillDocument,
        key: SkillKey,
        activations: List<UserSkillActivation>,
    ): Boolean =
        document.invocation.unsupportedExecutionFields.isEmpty() &&
            (
                document.invocation.modelInvocable || (
                    document.invocation.userInvocable &&
                        activations.any { it.key == key }
                )
            )

    fun setEnabled(
        key: SkillKey,
        enabled: Boolean,
        scope: SkillEnablementScope,
        sessionId: String? = null,
    ) {
        synchronized(lock) {
            refreshContributions()
            require(records.containsKey(key)) { "Unknown skill snapshot: ${key.name}" }
            when (scope) {
                SkillEnablementScope.GLOBAL -> {
                    globalOverrides[key] = enabled
                    persistState()
                }

                SkillEnablementScope.SESSION -> {
                    require(!sessionId.isNullOrBlank()) { "sessionId is required for session enablement" }
                    require(sessionId.none { it == '|' || it == '\n' || it == '\r' }) { "Invalid sessionId" }
                    sessionOverrides.getOrPut(sessionId) { linkedMapOf() }[key] = enabled
                    persistState()
                }
            }
        }
    }

    fun registerSnapshot(
        snapshot: SkillSnapshotRef,
        independent: Boolean = true,
    ): SkillKey =
        synchronized(lock) {
            val normalizedRoot = snapshotsRoot.toAbsolutePath().normalize()
            val normalizedDirectory = snapshot.directory.toAbsolutePath().normalize()
            require(normalizedDirectory.startsWith(normalizedRoot)) { "Snapshot is outside the installed root" }
            require(
                normalizedDirectory.fileName.toString() == snapshot.name &&
                    normalizedDirectory.parent.fileName.toString() == snapshot.snapshotHash &&
                    normalizedDirectory.parent.parent.fileName
                        .toString() == snapshot.name &&
                    normalizedDirectory.parent.parent.parent == normalizedRoot,
            ) { "Snapshot does not use the installed name/hash/name layout" }
            val preview = inspector.inspect(snapshot.directory, SkillSource.USER_IMPORTED)
            require(preview.name == snapshot.name && preview.snapshotHash == snapshot.snapshotHash) {
                "Snapshot identity does not match its content"
            }
            val document = SkillLoader().load(snapshot.directory, SkillSource.USER_IMPORTED)
            val key = document.catalogEntry.toKey(snapshot.snapshotHash)
            if (independent) onIndependentInstall(key)
            records[key] = SkillRecord(document, snapshot.directory)
            key
        }

    fun hasSnapshot(key: SkillKey): Boolean = synchronized(lock) { key in records }

    /** A compound publisher holds file references until its durable manifest is committed. */
    fun <T> withSnapshotReferences(block: () -> T): T = synchronized(lock, block)

    fun remove(key: SkillKey): Path =
        synchronized(lock) {
            require(key.source == SkillSource.USER_IMPORTED) { "Built-in and project skills cannot be removed" }
            beforeRemove(key)
            val record = records[key] ?: throw IllegalArgumentException("Unknown skill snapshot: ${key.name}")
            val source = requireNotNull(record.directory)
            val normalizedRoot = snapshotsRoot.toAbsolutePath().normalize()
            require(
                source.toAbsolutePath().normalize().startsWith(normalizedRoot),
            ) { "Snapshot is outside installed root" }
            val trashed = trashRoot.resolve("${key.name}-${key.snapshotHash}-${UUID.randomUUID()}")
            move(source, trashed)
            records.remove(key)
            globalOverrides.remove(key)
            sessionOverrides.values.forEach { it.remove(key) }
            persistState()
            trashed
        }

    /** Removes the imported snapshot and immediately purges its private trash copy. */
    fun removePermanentlyForPrivacy(key: SkillKey) {
        val trashed = remove(key)
        Files.walk(trashed).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private fun scanSnapshots() {
        Files.list(snapshotsRoot).use { names ->
            names
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .forEach { nameDirectory -> scanNameDirectory(nameDirectory) }
        }
    }

    private fun scanNameDirectory(nameDirectory: Path) {
        Files.list(nameDirectory).use { snapshots ->
            snapshots
                .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .forEach { container ->
                    runCatching {
                        val directory = container.resolve(nameDirectory.fileName.toString())
                        val preview = inspector.inspect(directory, SkillSource.USER_IMPORTED)
                        require(preview.name == nameDirectory.fileName.toString())
                        require(preview.snapshotHash == container.fileName.toString())
                        val document = SkillLoader().load(directory, SkillSource.USER_IMPORTED)
                        records[document.catalogEntry.toKey(preview.snapshotHash)] = SkillRecord(document, directory)
                    }
                }
        }
    }

    private fun isEnabled(
        key: SkillKey,
        sessionId: String?,
    ): Boolean {
        if (!sourceAvailable(key, sessionId) ||
            (key in contributionKeys && contributedDocuments(sessionId).none { it.catalogEntry.toKey() == key })
        ) {
            return false
        }
        val session = sessionId?.let { sessionOverrides[it]?.get(key) }
        return session ?: globalOverrides[key] ?: (key.source == SkillSource.BUILT_IN)
    }

    /** Replace host content facts; stale revisions cannot remain readable through an old key. */
    private fun refreshContributions() {
        val current = contributedDocuments(null).associateBy { it.catalogEntry.toKey() }
        require(current.keys.all { it.source == SkillSource.BUILT_IN }) { "Host content must use built-in source" }
        (contributionKeys - current.keys).forEach(records::remove)
        current.forEach { (key, document) -> records[key] = SkillRecord(document, null) }
        contributionKeys = current.keys
    }

    private fun resourcePath(
        root: Path,
        relativePath: String,
    ): Path {
        val parts = skillResourceParts(relativePath)
        var current = root
        parts.forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Skill resource path contains a symlink" }
        }
        val normalized = current.normalize()
        require(normalized.startsWith(root.normalize())) { "Skill resource escapes its snapshot" }
        require(Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) { "Skill resource is not a regular file" }
        require(Files.size(normalized) <= resourceLimitBytes) { "Skill resource exceeds the read limit" }
        return normalized
    }

    private fun loadState() {
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stateFile)) return
        Files.readAllLines(stateFile, StandardCharsets.UTF_8).forEach { line ->
            val fields = line.split('|')
            when (fields.firstOrNull()) {
                GLOBAL_SCOPE_MARKER -> loadGlobalState(fields)
                SESSION_SCOPE_MARKER -> loadSessionState(fields)
            }
        }
    }

    private fun loadGlobalState(fields: List<String>) {
        if (fields.size == GLOBAL_STATE_FIELD_COUNT) {
            val enabled = fields[1].toEnabledOrNull()
            val source = runCatching { SkillSource.valueOf(fields[2]) }.getOrNull()
            if (enabled != null && source != null) {
                val key = SkillKey(source, fields[3], fields[4])
                if (records.containsKey(key)) globalOverrides[key] = enabled
            }
        }
    }

    private fun loadSessionState(fields: List<String>) {
        if (fields.size == SESSION_STATE_FIELD_COUNT) {
            val sessionId = fields[1].takeIf { it.isNotBlank() }
            val enabled = fields[2].toEnabledOrNull()
            val source = runCatching { SkillSource.valueOf(fields[3]) }.getOrNull()
            if (sessionId != null && enabled != null && source != null) {
                val key = SkillKey(source, fields[4], fields[5])
                if (records.containsKey(key)) {
                    sessionOverrides.getOrPut(sessionId) { linkedMapOf() }[key] = enabled
                }
            }
        }
    }

    private fun persistState() {
        Files.createDirectories(stateFile.parent)
        val temporary = Files.createTempFile(stateFile.parent, ".skill-state-", ".tmp")
        val globalLines =
            globalOverrides.entries
                .sortedWith(compareBy({ it.key.name }, { it.key.source.name }, { it.key.snapshotHash }))
                .map { (key, enabled) ->
                    listOf(
                        GLOBAL_SCOPE_MARKER,
                        if (enabled) ENABLED_MARKER else DISABLED_MARKER,
                        key.source.name,
                        key.name,
                        key.snapshotHash,
                    ).joinToString("|")
                }
        val sessionLines =
            sessionOverrides.entries
                .sortedBy { it.key }
                .flatMap { (sessionId, overrides) ->
                    overrides.entries
                        .sortedWith(compareBy({ it.key.name }, { it.key.source.name }, { it.key.snapshotHash }))
                        .map { (key, enabled) ->
                            listOf(
                                SESSION_SCOPE_MARKER,
                                sessionId,
                                if (enabled) ENABLED_MARKER else DISABLED_MARKER,
                                key.source.name,
                                key.name,
                                key.snapshotHash,
                            ).joinToString("|")
                        }
                }
        val lines = globalLines + sessionLines
        val content = lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")
        Files.write(
            temporary,
            content.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        move(temporary, stateFile, replace = true)
    }

    private fun move(
        source: Path,
        target: Path,
        replace: Boolean = false,
    ) {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(source, target)
        }
    }

    private fun requireSafeRoot(
        root: Path,
        label: String,
    ) {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Skill $label root must be a regular, non-symlink directory"
        }
    }

    private fun decodeUtf8OrNull(bytes: ByteArray): String? =
        runCatching {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    private fun readBounded(path: Path): ByteArray =
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(RESOURCE_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= resourceLimitBytes) {
                    "Skill resource exceeds the read limit"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun String.toEnabledOrNull(): Boolean? =
        when (this) {
            ENABLED_MARKER -> true
            DISABLED_MARKER -> false
            else -> null
        }

    private fun SkillCatalogEntry.toKey(hash: String = contentHash): SkillKey = SkillKey(source, name, hash)

    private data class SkillRecord(
        val document: SkillDocument,
        val directory: Path?,
    )

    companion object {
        private const val GLOBAL_STATE_FIELD_COUNT = 5
        private const val SESSION_STATE_FIELD_COUNT = 6
        private const val GLOBAL_SCOPE_MARKER = "G"
        private const val SESSION_SCOPE_MARKER = "S"
        private const val ENABLED_MARKER = "+"
        private const val DISABLED_MARKER = "-"
        private const val RESOURCE_BUFFER_SIZE = 8192
    }
}
