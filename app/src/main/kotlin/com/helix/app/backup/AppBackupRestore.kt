package com.helix.app.backup

import android.content.Context
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.backup.AppBackupArchive
import com.helix.core.storage.backup.CredentialBackupCipher
import com.helix.core.storage.reminder.ReminderStore
import com.helix.core.storage.scheduled.ScheduledAgentStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.InputStream

/** Builds a closed profile privately; no method here writes live product roots. */
internal class AppBackupRestore(
    private val context: Context,
) {
    @Suppress("TooGenericExceptionCaught") // Clean only owned staging, preserve cancellation and original failures.
    suspend fun prepare(
        input: InputStream,
        directory: File,
        includeCredentials: Boolean,
        password: CharArray,
        checkActive: () -> Unit,
    ): PreparedProfileRestore {
        require(directory.isDirectory || directory.mkdirs())
        try {
            val snapshot = readArchive(input, directory, includeCredentials, checkActive)
            val state = snapshot.state
            val credentials =
                if (includeCredentials) {
                    decrypt(
                        snapshot.members.getValue("credentials.enc"),
                        password,
                        contextHash(snapshot.members.getValue("state.json")),
                    )
                } else {
                    emptyMap()
                }
            val records = state.getValue("records") as JsonObject
            val attachments =
                AppBackupCapture
                    .rows(
                        records,
                        "artifacts",
                    ).map { AppBackupCapture.text(it, "id") }
                    .toSet()
            val identities = PortableRestoreRecords(records, attachments, credentials)
            val files = AppBackupProfileFiles(directory, snapshot, identities, checkActive)
            files.stage()
            restoreMain(files, identities, checkActive)
            restoreAuxiliary(files, state, checkActive)
            val preferences = state.getValue("preferences") as JsonObject
            files.roots.putAll(PortablePreferences.stage(preferences, File(files.profile, "preferences")))
            listOf("files/workspaces/saf-grants.json", "files/mcp_oauth_attempts", "files/execution-admission")
                .forEach { files.roots[it] = null }
            File(directory, "restore-state.json").writeText(state.toString())
            val index = JsonObject(snapshot.members.mapValues { JsonPrimitive(it.value.name) })
            File(directory, "archive-index.json").writeText(index.toString())
            return PreparedProfileRestore(
                directory,
                files.roots,
                state,
                preferences,
                snapshot.groups["library"].orEmpty(),
                snapshot.members,
                includeCredentials,
            )
        } catch (failure: Exception) {
            safeDeleteBackupTree(directory)
            throw failure
        } finally {
            password.fill('\u0000')
        }
    }

    private fun readArchive(
        input: InputStream,
        directory: File,
        credentials: Boolean,
        check: () -> Unit,
    ): AppBackupSnapshot {
        val members = AppBackupArchive().read(input, File(directory, "archive"), check)
        val state = Json.parseToJsonElement(members.getValue("state.json").readText()) as JsonObject
        require(state.keys == STATE_KEYS) { "BACKUP_STATE_INVALID" }
        val format = state.getValue("format").jsonPrimitive
        require(!format.isString && format.long == 1L)
        java.util.UUID.fromString(state.getValue("backupId").jsonPrimitive.content)
        val time = state.getValue("capturedAt").jsonPrimitive
        require(!time.isString && time.long >= 0)
        val encrypted = state.getValue("encryptedCredentials").jsonPrimitive.boolean
        require(encrypted == members.containsKey("credentials.enc")) { "BACKUP_CREDENTIAL_ENVELOPE_MISSING" }
        val groups = PortableFileSet.validate(state.getValue("files") as JsonArray, members)
        val library = groups["library"].orEmpty()
        require(state.getValue("libraryIncluded").jsonPrimitive.boolean || library.isEmpty())
        require(library.size <= 1024 && library.sumOf { it.getValue("size").jsonPrimitive.long } <= 64L * 1024 * 1024) {
            "BACKUP_LIBRARY_LIMIT"
        }
        val blobs =
            groups.values
                .flatten()
                .map { "blobs/${it.getValue("sha256").jsonPrimitive.content}" }
                .toSet()
        val expected = blobs + "state.json" + if (encrypted) setOf("credentials.enc") else emptySet()
        require(members.keys == expected) { "BACKUP_UNDECLARED_CONTENT" }
        require(!credentials || encrypted) { "BACKUP_NO_CREDENTIALS" }
        return AppBackupSnapshot(state, members, groups)
    }

    private fun restoreMain(
        files: AppBackupProfileFiles,
        identities: PortableRestoreRecords,
        checkActive: () -> Unit,
    ) {
        val database = File(files.profile, "helix.db")
        val storage = HelixStorage.open(context, database.absolutePath, files.content)
        try {
            storage.portableState.restore(identities.records, checkActive)
            files.clones.forEach { (id, target) -> storage.portableState.configureManagedClone(id, target) }
            storage.plans.list().forEach {
                checkActive()
                storage.plans.resolve(it.id)
            }
            storage.experts.list()
            storage.goals.list().forEach {
                checkActive()
                storage.goals.resolve(it.id)
            }
            val secrets = File(files.profile, "secrets")
            storage.portableState.sealPortableCredentials(secrets, identities.credentials)
            check(secrets.isDirectory || secrets.mkdirs())
            files.roots["files/helix-secrets"] = secrets
            storage.portableState.checkpoint()
        } finally {
            storage.close()
        }
        addDatabaseRoots(files.roots, database, "helix.db")
    }

    private fun restoreAuxiliary(
        files: AppBackupProfileFiles,
        state: JsonObject,
        check: () -> Unit,
    ) {
        val records = state.getValue("records") as JsonObject
        val sessions = AppBackupCapture.rows(records, "sessions").map { AppBackupCapture.text(it, "id") }.toSet()
        val turns = AppBackupCapture.rows(records, "turns").map { AppBackupCapture.text(it, "id") }.toSet()
        val schedules = File(files.profile, "scheduled-agents.db")
        val scheduleStore = ScheduledAgentStore(context, schedules.absolutePath)
        try {
            scheduleStore.portable.restore(state.getValue("schedules") as JsonObject, sessions, turns, check)
        } finally {
            scheduleStore.close()
        }
        addDatabaseRoots(files.roots, schedules, "scheduled-agents.db")
        val reminders = File(files.profile, "reminders.db")
        val reminderStore = ReminderStore(context, reminders.absolutePath)
        try {
            reminderStore.portable.restore(state.getValue("reminders") as JsonObject, sessions, turns, check)
        } finally {
            reminderStore.close()
        }
        addDatabaseRoots(files.roots, reminders, "reminders.db")
    }

    private fun decrypt(
        file: File,
        password: CharArray,
        contextSha256: String,
    ): Map<String, String> {
        val bytes = CredentialBackupCipher.decrypt(file.readBytes(), password, contextSha256)
        try {
            val values = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as JsonObject
            require(values.size <= 4096)
            return values.mapValues { (_, value) ->
                require(value.jsonPrimitive.isString && value.jsonPrimitive.content.length <= 1_048_576)
                value.jsonPrimitive.content
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun contextHash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun addDatabaseRoots(
        roots: MutableMap<String, File?>,
        file: File,
        name: String,
    ) {
        check(file.isFile)
        roots["databases/$name"] = file
        listOf("-wal", "-shm", "-journal").forEach { roots["databases/$name$it"] = null }
    }

    private companion object {
        val STATE_KEYS =
            setOf(
                "format",
                "backupId",
                "capturedAt",
                "records",
                "preferences",
                "schedules",
                "reminders",
                "files",
                "workspacePayloads",
                "missing",
                "libraryIncluded",
                "encryptedCredentials",
            )
    }
}

internal class PreparedProfileRestore(
    val directory: File,
    val roots: Map<String, File?>,
    val state: JsonObject,
    val preferences: JsonObject,
    val library: List<JsonObject>,
    val members: Map<String, File>,
    val restoresCredentials: Boolean,
) : AutoCloseable {
    private val lifecycle = Any()
    private var queued = false
    private var queueing = false
    private var closing = false

    fun beginQueue() =
        synchronized(lifecycle) {
            check(!closing && !queueing && !queued) { "RESTORE_PREVIEW_CLOSED" }
            queueing = true
        }

    fun endQueue(preserve: Boolean) {
        val cleanup =
            synchronized(lifecycle) {
                queued = preserve
                queueing = false
                closing && !queued
            }
        if (cleanup) safeDeleteBackupTree(directory)
    }

    override fun close() {
        val cleanup =
            synchronized(lifecycle) {
                closing = true
                !queued && !queueing
            }
        if (cleanup) safeDeleteBackupTree(directory)
    }
}
