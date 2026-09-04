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

@Suppress("TooManyFunctions")
class SkillRepository(
    private val snapshotsRoot: Path,
    private val stateFile: Path,
    private val trashRoot: Path,
    builtIns: List<SkillDocument> = BuiltInSkills.documents(),
    private val resourceLimitBytes: Long = 512 * 1024,
) {
    private val lock = Any()
    private val inspector = SkillSnapshotInspector(SkillImportLimits(), SkillLoader())
    private val records = linkedMapOf<SkillKey, SkillRecord>()
    private val globalOverrides = linkedMapOf<SkillKey, Boolean>()
    private val sessionOverrides = linkedMapOf<String, MutableMap<SkillKey, Boolean>>()

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
            records.entries
                .map { (key, record) ->
                    SkillListItem(key, record.document.catalogEntry.description, isEnabled(key, sessionId))
                }.sortedWith(compareBy({ it.key.name }, { it.key.source.name }, { it.key.snapshotHash }))
        }

    fun read(
        key: SkillKey,
        sessionId: String? = null,
    ): SkillDocument =
        synchronized(lock) {
            require(isEnabled(key, sessionId)) { "Skill is disabled: ${key.name}" }
            records[key]?.document ?: throw IllegalArgumentException("Unknown skill snapshot: ${key.name}")
        }

    fun readResource(
        key: SkillKey,
        relativePath: String,
        sessionId: String? = null,
    ): SkillResource =
        synchronized(lock) {
            require(isEnabled(key, sessionId)) { "Skill is disabled: ${key.name}" }
            val record = records[key] ?: throw IllegalArgumentException("Unknown skill snapshot: ${key.name}")
            val root = record.directory ?: throw IllegalArgumentException("Built-in skill has no external resources")
            val safePath = resourcePath(root, relativePath)
            val bytes = readBounded(safePath)
            val text = decodeUtf8OrNull(bytes)
            SkillResource(
                relativePath = relativePath,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
                encoding = if (text == null) "base64" else "utf-8",
                content = text ?: Base64.getEncoder().encodeToString(bytes),
            )
        }

    fun setEnabled(
        key: SkillKey,
        enabled: Boolean,
        scope: SkillEnablementScope,
        sessionId: String? = null,
    ) {
        synchronized(lock) {
            require(records.containsKey(key)) { "Unknown skill snapshot: ${key.name}" }
            when (scope) {
                SkillEnablementScope.GLOBAL -> {
                    globalOverrides[key] = enabled
                    persistState()
                }

                SkillEnablementScope.SESSION -> {
                    require(!sessionId.isNullOrBlank()) { "sessionId is required for session enablement" }
                    sessionOverrides.getOrPut(sessionId) { linkedMapOf() }[key] = enabled
                }
            }
        }
    }

    fun registerSnapshot(snapshot: SkillSnapshotRef): SkillKey =
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
            records[key] = SkillRecord(document, snapshot.directory)
            key
        }

    fun remove(key: SkillKey): Path =
        synchronized(lock) {
            require(key.source == SkillSource.USER_IMPORTED) { "Built-in and project skills cannot be removed" }
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
        val session = sessionId?.let { sessionOverrides[it]?.get(key) }
        if (session != null) return session
        return globalOverrides[key] ?: (key.source == SkillSource.BUILT_IN)
    }

    private fun resourcePath(
        root: Path,
        relativePath: String,
    ): Path {
        require(relativePath.isNotBlank() && relativePath.length <= MAX_RESOURCE_PATH_LENGTH) {
            "Invalid skill resource path"
        }
        require(!relativePath.startsWith('/') && '\\' !in relativePath) { "Skill resource path must be relative" }
        val parts = relativePath.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Skill resource path contains traversal" }
        require(parts.first() == "references" || parts.first() == "assets") {
            "Only references and assets can be read as skill resources"
        }
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
            if (fields.size != STATE_FIELD_COUNT) return@forEach
            if (fields[0] != ENABLED_MARKER && fields[0] != DISABLED_MARKER) return@forEach
            val source = runCatching { SkillSource.valueOf(fields[1]) }.getOrNull() ?: return@forEach
            val key = SkillKey(source, fields[2], fields[3])
            if (records.containsKey(key)) globalOverrides[key] = fields[0] == ENABLED_MARKER
        }
    }

    private fun persistState() {
        Files.createDirectories(stateFile.parent)
        val temporary = Files.createTempFile(stateFile.parent, ".skill-state-", ".tmp")
        val content =
            globalOverrides.entries
                .sortedWith(compareBy({ it.key.name }, { it.key.source.name }, { it.key.snapshotHash }))
                .joinToString("\n", postfix = if (globalOverrides.isEmpty()) "" else "\n") { (key, enabled) ->
                    listOf(
                        if (enabled) ENABLED_MARKER else DISABLED_MARKER,
                        key.source.name,
                        key.name,
                        key.snapshotHash,
                    ).joinToString("|")
                }
        Files.writeString(temporary, content, StandardOpenOption.TRUNCATE_EXISTING)
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

    private fun SkillCatalogEntry.toKey(hash: String = contentHash): SkillKey = SkillKey(source, name, hash)

    private data class SkillRecord(
        val document: SkillDocument,
        val directory: Path?,
    )

    companion object {
        private const val MAX_RESOURCE_PATH_LENGTH = 512
        private const val STATE_FIELD_COUNT = 4
        private const val ENABLED_MARKER = "+"
        private const val DISABLED_MARKER = "-"
        private const val RESOURCE_BUFFER_SIZE = 8192
    }
}
