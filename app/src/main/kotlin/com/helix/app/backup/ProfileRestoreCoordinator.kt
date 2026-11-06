package com.helix.app.backup

import android.content.Context
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.backup.RestoreFileTransaction
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.content.MediaStoreDocuments
import com.helix.core.storage.knowledge.PortableKnowledgeRestore
import com.helix.core.workspace.AtomicFileWriter
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal object ProfileReplacement {
    val lock = Any()

    @Volatile var consumersStarted = false

    @Volatile var restoreAttempted = false

    fun pending(context: Context): Boolean = File(context.noBackupFilesDir, "profile-restore/pending.json").exists()
}

/** Durable pointer and rollback run before databases, account restoration or reminder consumers open. */
@Suppress("TooManyFunctions") // Cohesive boot-time transaction coordinator; not a second restore authority.
internal class ProfileRestoreCoordinator(
    private val context: Context,
    private val publicDocuments: () -> DocumentStorage = { MediaStoreDocuments(context, "library") },
) {
    private val home = File(context.noBackupFilesDir, "profile-restore")
    private val pointer = File(home, "pending.json")
    private val data = requireNotNull(context.filesDir.parentFile)

    fun stageDirectory(): File = File(home, "stage-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }

    fun queue(
        prepared: PreparedProfileRestore,
        storage: HelixStorage,
        ownership: ExecutionOwnership,
    ) = synchronized(ProfileReplacement.lock) {
        prepared.beginQueue()
        try {
            ownership.withIdleProfile {
                check(!pointer.exists()) { "RESTORE_ALREADY_PENDING" }
                check(storage.turns.listActive().all { it.state == "NEEDS_REVIEW" }) { "RESTORE_TURNS_ACTIVE" }
                require(prepared.directory.canonicalFile.parentFile == home.canonicalFile)
                val previousPreferences = PortablePreferences.capture(context).toString()
                File(prepared.directory, "previous-preferences.json").writeText(previousPreferences)
                val transaction = transaction(prepared.directory)
                transaction.queue(prepared.roots)
                val metadata =
                    JsonObject(
                        mapOf(
                            "directory" to JsonPrimitive(prepared.directory.name),
                            "phase" to JsonPrimitive("QUEUED"),
                        ),
                    )
                AtomicFileWriter.writeAtomic(pointer.toPath(), metadata.toString().toByteArray())
            }
        } finally {
            // A persistence exception may follow a committed atomic rename. Keep the stage
            // whenever a durable pointer exists; never delete an uncertain queued restore.
            prepared.endQueue(preserve = pointer.exists())
        }
    }

    /** Startup-only, before consumers: roll back ordinary failures and rethrow, never report partial success. */
    @Suppress("TooGenericExceptionCaught")
    fun recoverBeforeConsumers() =
        synchronized(ProfileReplacement.lock) {
            cleanupCompleted()
            if (!pointer.exists()) return@synchronized
            ProfileReplacement.restoreAttempted = true
            check(!ProfileReplacement.consumersStarted) { "RESTORE_CLOSE_AND_REOPEN" }
            val plan = readPointer()
            val directory = directory(plan)
            val transaction = transaction(directory)
            val library = RestoreLibraryDocuments(publicDocuments(), File(directory, "public-publications.json"))
            abortInterrupted(plan, directory, transaction, library)
            val stateFile = File(directory, "restore-state.json")
            require(stateFile.length() <= 64L * 1024 * 1024)
            val state = Json.parseToJsonElement(stateFile.readText()) as JsonObject
            val members = archiveMembers(directory)
            val descriptors =
                (state.getValue("files") as kotlinx.serialization.json.JsonArray)
                    .map { it as JsonObject }
                    .filter { it.getValue("group").jsonPrimitive.content == "library" }
            try {
                if (transaction.phase() != "COMMITTED") {
                    val applying = JsonObject(plan + ("phase" to JsonPrimitive("APPLYING")))
                    AtomicFileWriter.writeAtomic(pointer.toPath(), applying.toString().toByteArray())
                    if (descriptors.isNotEmpty()) {
                        runBlocking {
                            PortableKnowledgeRestore.publish(
                                RestoreLibrarySource(descriptors, members),
                                library,
                                state.getValue("libraryIncluded").jsonPrimitive.boolean,
                            )
                        }
                    }
                    val outcome = transaction.recover()
                    check(outcome == RestoreFileTransaction.Outcome.RESTORED) { "RESTORE_REPLACEMENT_FAILED" }
                }
                PortablePreferences.refreshLoaded(context, state.getValue("preferences") as JsonObject)
            } catch (failure: Exception) {
                if (transaction.phase() == "COMMITTED") {
                    transaction.requestRollback()
                    transaction.recover()
                } else if (transaction.phase() == "PREPARED") {
                    transaction.abortPrepared()
                }
                library.rollback()
                restorePreviousPreferences(directory)
                check(pointer.delete())
                throw failure
            }
        }

    private fun abortInterrupted(
        plan: JsonObject,
        directory: File,
        transaction: RestoreFileTransaction,
        library: RestoreLibraryDocuments,
    ) {
        if (plan.getValue("phase").jsonPrimitive.content == "ROLLBACK") {
            when (transaction.phase()) {
                "PREPARED" -> {
                    transaction.abortPrepared()
                }

                "COMMITTED" -> {
                    transaction.requestRollback()
                    transaction.recover()
                }

                else -> {
                    transaction.recover()
                }
            }
            library.rollback()
            restorePreviousPreferences(directory)
            check(pointer.delete())
            error("RESTORE_FAILED_ROLLED_BACK")
        }
        if (plan.getValue("phase").jsonPrimitive.content == "APPLYING" && transaction.phase() != "COMMITTED") {
            if (transaction.phase() == "PREPARED") transaction.abortPrepared() else transaction.recover()
            library.rollback()
            restorePreviousPreferences(directory)
            check(pointer.delete())
            error("RESTORE_INTERRUPTED_ROLLED_BACK")
        }
    }

    fun markConsumerFailure() =
        synchronized(ProfileReplacement.lock) {
            if (pointer.exists()) {
                val plan = readPointer()
                val marked = JsonObject(plan + ("phase" to JsonPrimitive("ROLLBACK")))
                AtomicFileWriter.writeAtomic(pointer.toPath(), marked.toString().toByteArray())
            }
        }

    private fun restorePreviousPreferences(directory: File) {
        val content = File(directory, "previous-preferences.json").readText()
        val previous = Json.parseToJsonElement(content) as JsonObject
        PortablePreferences.refreshLoaded(context, previous, resetAuthorization = false)
    }

    /** Keep rollback roots until all selected consumers have initialized successfully. */
    fun completeAfterConsumers() =
        synchronized(ProfileReplacement.lock) {
            if (!pointer.exists()) return@synchronized
            val directory = directory(readPointer())
            check(transaction(directory).phase() == "COMMITTED")
            AtomicFileWriter.writeAtomic(File(home, "cleanup-${directory.name}").toPath(), byteArrayOf())
            check(pointer.delete())
            cleanupCompleted()
        }

    private fun cleanupCompleted() {
        // Completion marker and pending deletion are separate durable writes. A crash between
        // them must not delete the stage still needed by recovery or rollback.
        val active = if (pointer.exists()) directory(readPointer()).name else null
        home.listFiles().orEmpty().filter { it.name.matches(Regex("cleanup-stage-[a-f0-9-]{36}")) }.forEach { marker ->
            if (marker.name.removePrefix("cleanup-") == active) return@forEach
            try {
                safeDeleteBackupTree(File(home, marker.name.removePrefix("cleanup-")))
                if (!marker.delete()) throw java.io.IOException("RESTORE_CLEANUP_DEFERRED")
            } catch (_: java.io.IOException) {
                // Replacement has committed; retain a durable cleanup receipt for the next start.
                android.util.Log.w("HelixRestore", "Old restore staging cleanup deferred")
            }
        }
    }

    private fun readPointer(): JsonObject {
        require(pointer.length() <= 4096)
        val value = Json.parseToJsonElement(pointer.readText()) as JsonObject
        require(value.keys == setOf("directory", "phase"))
        require(value.getValue("phase").jsonPrimitive.content in setOf("QUEUED", "APPLYING", "ROLLBACK"))
        return value
    }

    private fun directory(plan: JsonObject): File {
        val name = plan.getValue("directory").jsonPrimitive.content
        require(name.matches(Regex("stage-[a-f0-9-]{36}")))
        return File(home, name).also { require(it.isDirectory && it.canonicalFile.parentFile == home.canonicalFile) }
    }

    private fun archiveMembers(directory: File): Map<String, File> {
        require(File(directory, "archive-index.json").length() <= 4L * 1024 * 1024)
        val index = Json.parseToJsonElement(File(directory, "archive-index.json").readText()) as JsonObject
        return index.mapValues { (_, value) ->
            val name = value.jsonPrimitive.content
            require(name.matches(Regex("[0-9]+")))
            File(directory, "archive/$name")
        }
    }

    private fun transaction(directory: File) = RestoreFileTransaction(data, directory, ::permitted)

    private fun permitted(path: String): Boolean =
        path in
            setOf(
                "files/helix-content",
                "files/memory",
                "files/skills",
                "files/helix-content-plans",
                "files/marketplaces",
                "files/agent-library-history/library",
                "files/native-hosts.txt",
                "files/helix-secrets",
                "files/workspaces/saf-grants.json",
                "files/mcp_oauth_attempts",
                "files/execution-admission",
            ) || path.matches(Regex("files/workspaces/app/restored-[a-f0-9-]{36}")) ||
            path.matches(Regex("files/workspaces/managed/ws-[a-f0-9-]{36}")) ||
            path.matches(Regex("databases/(helix|scheduled-agents|reminders)\\.db(-wal|-shm|-journal)?")) ||
            PortablePreferences.permittedRoot(path)
}
