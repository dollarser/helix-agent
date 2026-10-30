package com.helix.app.backup

import android.content.Context
import com.helix.app.files.FileManagerService
import com.helix.app.knowledge.KnowledgeService
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.backup.CredentialBackupCipher
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.reminder.ReminderStore
import com.helix.core.storage.scheduled.ScheduledAgentStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal data class AppBackupSelection(
    val library: Boolean = false,
    val workspaces: Boolean = false,
    val credentials: Boolean = false,
)

internal data class AppBackupOverview(
    val sessions: Int,
    val messages: Int,
    val experts: Int,
    val tasks: Int,
    val files: Int,
    val missing: Int,
)

internal class PreparedAppBackup(
    val directory: File,
    val archiveFiles: Map<String, File>,
    val overview: AppBackupOverview,
    val encryptedCredentials: Boolean,
) : AutoCloseable {
    val writing =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    @Volatile var closing = false

    override fun close() {
        closing = true
        if (!writing.get()) safeDeleteBackupTree(directory)
    }
}

@Suppress("TooManyFunctions") // One read-only snapshot authority, with separate bounded collectors.
internal class AppBackupCapture(
    private val context: Context,
    private val storage: HelixStorage,
    private val files: FileManagerService,
    private val knowledge: KnowledgeService,
) {
    suspend fun prepare(
        directory: File,
        selection: AppBackupSelection,
        password: CharArray,
        checkActive: () -> Unit,
    ): PreparedAppBackup {
        val records = storage.portableState.capture(checkActive)
        val fileSet = PortableFileSet(directory, checkActive)
        captureBodies(fileSet, records, checkActive)
        capturePrivateFiles(fileSet)
        val missing = captureArtifacts(fileSet, records, directory, checkActive).toMutableList()
        val payloads = captureWorkspaces(fileSet, records, directory, selection.workspaces, missing, checkActive)
        if (selection.library) {
            knowledge.backupLibrary().forEach { (path, bytes) ->
                fileSet.addBytes("library", path.removePrefix("library/"), bytes)
            }
        }
        val state =
            buildJsonObject {
                put("format", 1)
                put("backupId", UUID.randomUUID().toString())
                put("capturedAt", System.currentTimeMillis())
                put("records", records)
                put("preferences", PortablePreferences.capture(context))
                put("schedules", captureRules(checkActive))
                put("reminders", captureReminders(checkActive))
                put("files", fileSet.metadata)
                put("workspacePayloads", payloads)
                put("missing", JsonArray(missing))
                put("libraryIncluded", selection.library)
                put("encryptedCredentials", selection.credentials)
            }
        val bytes = state.toString().toByteArray()
        val encrypted =
            if (selection.credentials) {
                credentialFile(records, password, directory, FileContentStore.sha256Hex(bytes))
            } else {
                null
            }
        require(bytes.size.toLong() + (encrypted?.length() ?: 0) <= 64L * 1024 * 1024) { "BACKUP_METADATA_LIMIT" }
        val stateFile = File(directory, "state.json").apply { writeBytes(bytes) }
        val entries =
            fileSet.archiveFiles + ("state.json" to stateFile) +
                (encrypted?.let { mapOf("credentials.enc" to it) } ?: emptyMap())
        return PreparedAppBackup(directory, entries, overview(state), encrypted != null)
    }

    private fun captureBodies(
        set: PortableFileSet,
        records: JsonObject,
        checkActive: () -> Unit,
    ) {
        storage.plans.list().forEach {
            checkActive()
            storage.plans.resolve(it.id)
        }
        val source = requireNotNull(storage.contentStore as? FileContentStore)
        rows(records, "messages").filter { text(it, "role") in setOf("USER", "ASSISTANT") }.forEach { row ->
            row["contentRef"]?.takeUnless { it == JsonNull }?.let {
                checkActive()
                val reference =
                    com.helix.core.storage.content.ContentRef
                        .parse(it.jsonPrimitive.content)
                source.readBounded(reference, 64 * 1024 * 1024)
            }
        }
        storage.portableState.references(records).forEach { reference ->
            val file = source.verifiedBinary(reference, 512L * 1024 * 1024, checkActive)
            set.add("content", reference.relativePath, file, reference.sha256)
        }
    }

    private fun capturePrivateFiles(set: PortableFileSet) {
        mapOf("memory" to "memory", "plans" to "helix-content-plans", "history" to "agent-library-history/library")
            .forEach { (group, path) -> set.tree(group, File(context.filesDir, path)) }
        set.tree("skills", File(context.filesDir, "skills/snapshots"), "snapshots")
        File(context.filesDir, "marketplaces").listFiles().orEmpty().filter { it.name.endsWith(".catalog") }.forEach {
            set.add("marketplaces", it.name, it)
        }
        File(context.filesDir, "skills/enablement.txt").takeIf(File::isFile)?.let {
            set.add("skills", "enablement.txt", it)
        }
        File(
            context.filesDir,
            "native-hosts.txt",
        ).takeIf(File::isFile)?.let { set.add("network", "native-hosts.txt", it) }
    }

    private fun captureArtifacts(
        set: PortableFileSet,
        records: JsonObject,
        directory: File,
        checkActive: () -> Unit,
    ): List<JsonElement> {
        val missing = mutableListOf<JsonElement>()
        rows(records, "artifacts").forEach { artifact ->
            val id = text(artifact, "id")
            val reference = parseReference(text(artifact, "relativePath"))
            val temporary = File.createTempFile("attachment-", ".pending", directory)
            try {
                files.copyForBackup(reference.first, reference.second, temporary, 512L * 1024 * 1024, checkActive)
                set.add("attachment", id, temporary, text(artifact, "sha256"))
            } catch (
                cancel: kotlinx.coroutines.CancellationException,
            ) {
                throw cancel
            } catch (
                _: java.io.IOException,
            ) {
                missing += missingEntry("attachment", id)
            } catch (
                _: IllegalArgumentException,
            ) {
                missing += missingEntry("attachment", id)
            } finally {
                temporary.delete()
            }
        }
        return missing
    }

    private fun captureWorkspaces(
        set: PortableFileSet,
        records: JsonObject,
        directory: File,
        whole: Boolean,
        missing: MutableList<JsonElement>,
        checkActive: () -> Unit,
    ): JsonArray {
        val payloads = mutableListOf<JsonElement>()
        if (whole) {
            copyWorkspace(set, "app", "", directory, checkActive)
            payloads += JsonPrimitive("app")
        }
        rows(records, "workspaces").forEach { workspace ->
            val id = text(workspace, "id")
            if (text(workspace, "availability") != "READY") {
                if (whole) missing += missingEntry("workspace", id)
            } else if (whole) {
                copyWorkspace(set, id, "", directory, checkActive)
                payloads += JsonPrimitive(id)
            } else {
                captureInstructions(set, id, directory, checkActive)
            }
        }
        return JsonArray(payloads)
    }

    private fun captureInstructions(
        set: PortableFileSet,
        id: String,
        directory: File,
        checkActive: () -> Unit,
    ) {
        val listing = files.listing(id, "")
        require(!listing.truncated) { "BACKUP_DIRECTORY_LIMIT" }
        val instructions = setOf("AGENTS.md", "CLAUDE.md", "HELIX.md")
        listing.entries.filter { it.name in instructions && !it.isDirectory }.forEach { entry ->
            val temporary = File.createTempFile("instruction-", ".pending", directory)
            try {
                files.copyForBackup(id, entry.relativePath, temporary, 1_048_576, checkActive)
                set.add("instructions", "$id/${entry.relativePath}", temporary)
            } finally {
                temporary.delete()
            }
        }
    }

    private fun captureRules(checkActive: () -> Unit): JsonObject {
        val store = ScheduledAgentStore(context)
        try {
            return store.portable.capture(checkActive)
        } finally {
            store.close()
        }
    }

    private fun captureReminders(checkActive: () -> Unit): JsonObject {
        val store = ReminderStore(context)
        try {
            return store.portable.capture(checkActive)
        } finally {
            store.close()
        }
    }

    private fun copyWorkspace(
        set: PortableFileSet,
        id: String,
        relative: String,
        staging: File,
        checkActive: () -> Unit,
    ) {
        require(relative.count { it == '/' } < 32) { "BACKUP_DEPTH_LIMIT" }
        val listing = files.listing(id, relative)
        require(!listing.truncated) { "BACKUP_DIRECTORY_LIMIT" }
        val generated = setOf(".helix", ".trash", "cache")
        listing.entries.filterNot { relative.isEmpty() && it.name in generated }.forEach { entry ->
            checkActive()
            if (entry.isDirectory) {
                copyWorkspace(set, id, entry.relativePath, staging, checkActive)
            } else {
                val temporary = File.createTempFile("workspace-", ".pending", staging)
                try {
                    files.copyForBackup(id, entry.relativePath, temporary, 512L * 1024 * 1024, checkActive)
                    set.add("workspace", "$id/${entry.relativePath}", temporary)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    private fun credentialFile(
        records: JsonObject,
        password: CharArray,
        directory: File,
        contextSha256: String,
    ): File {
        val values =
            allowedCredentialAliases(records)
                .filter { storage.secrets.contains(SecretAlias(it)) }
                .associateWith { JsonPrimitive(storage.secrets.get(SecretAlias(it))) }
        val bytes = JsonObject(values).toString().toByteArray()
        try {
            val encrypted = CredentialBackupCipher.encrypt(bytes, password, contextSha256)
            return File(directory, "credentials.enc").apply { writeBytes(encrypted) }
        } finally {
            bytes.fill(0)
            password.fill('\u0000')
        }
    }

    companion object {
        fun rows(
            state: JsonObject,
            table: String,
        ): List<JsonObject> = (state.getValue(table) as JsonArray).map { it as JsonObject }

        fun text(
            row: JsonObject,
            key: String,
        ): String = row.getValue(key).jsonPrimitive.content

        fun allowedCredentialAliases(records: JsonObject): Set<String> {
            val providers =
                rows(records, "provider_configs")
                    .filter { text(it, "provisioningKind") == "USER_CONFIGURED" && text(it, "authKind") == "SECRET" }
                    .mapNotNull { it["secretAlias"]?.takeUnless { v -> v == JsonNull }?.jsonPrimitive?.content }
            val endpoints =
                listOf("mcp_servers", "a2a_agents").flatMap { table ->
                    rows(records, table).mapNotNull {
                        it["authAlias"]
                            ?.takeUnless { v ->
                                v == JsonNull
                            }?.jsonPrimitive
                            ?.content
                    }
                }
            return (providers + endpoints)
                .filterNot { it == "helix-account-session" || it.startsWith("oauth.") || ".oauth." in it }
                .onEach { SecretAlias(it) }
                .toSet()
        }

        fun overview(state: JsonObject): AppBackupOverview {
            val records = state.getValue("records") as JsonObject
            val rules = state.getValue("schedules") as JsonObject
            val reminders = state.getValue("reminders") as JsonObject
            val tasks =
                (rules.getValue("scheduled_rules") as JsonArray).size +
                    (reminders.getValue("reminders") as JsonArray).size
            return AppBackupOverview(
                rows(records, "sessions").size,
                rows(records, "messages").size,
                rows(records, "experts").size,
                tasks,
                (state.getValue("files") as JsonArray).size,
                (state.getValue("missing") as JsonArray).size,
            )
        }

        private fun missingEntry(
            kind: String,
            id: String,
        ) = JsonObject(mapOf("kind" to JsonPrimitive(kind), "id" to JsonPrimitive(id)))

        private fun parseReference(reference: String): Pair<String, String> {
            require(reference.startsWith("scope:"))
            val remainder = reference.removePrefix("scope:")
            val id = remainder.substringBefore(':')
            val path = remainder.substringAfter(':')
            com.helix.core.workspace
                .FileScopePath(id, path)
            return id to path
        }
    }
}

internal fun safeDeleteBackupTree(root: File) {
    if (!Files.exists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root.toPath(),
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
