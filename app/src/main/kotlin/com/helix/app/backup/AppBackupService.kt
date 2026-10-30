package com.helix.app.backup

import android.content.Context
import android.net.Uri
import com.helix.app.files.FileManagerService
import com.helix.app.knowledge.KnowledgeService
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.backup.AppBackupArchive
import com.helix.core.storage.content.MediaStoreDocuments
import com.helix.core.storage.knowledge.PortableKnowledgeRestore
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/** User-directed local SAF backup. No login sessions, permissions or pending effects are transferred. */
@Suppress("TooGenericExceptionCaught") // Cleanup owned staging and rethrow every failure, including cancellation.
class AppBackupService(
    private val context: Context,
    private val storage: HelixStorage,
    private val files: FileManagerService,
    private val knowledge: KnowledgeService,
    private val ownership: ExecutionOwnership,
) {
    private val coordinator = ProfileRestoreCoordinator(context)

    internal suspend fun prepareExport(
        selection: AppBackupSelection,
        password: CharArray,
    ): PreparedAppBackup {
        var result: PreparedAppBackup? = null
        try {
            return withContext(Dispatchers.IO) {
                val directory = coordinator.stageDirectory()
                val operation = kotlin.coroutines.coroutineContext
                try {
                    AppBackupCapture(context, storage, files, knowledge)
                        .prepare(directory, selection, password) {
                            operation.ensureActive()
                        }.also { result = it }
                } catch (failure: Exception) {
                    safeDeleteBackupTree(directory)
                    throw failure
                }
            }
        } catch (failure: Exception) {
            result?.close()
            throw failure
        } finally {
            password.fill('\u0000')
        }
    }

    internal suspend fun export(
        uri: Uri,
        prepared: PreparedAppBackup,
    ) = withContext(Dispatchers.IO) {
        val operation = kotlin.coroutines.coroutineContext
        check(!prepared.closing && prepared.writing.compareAndSet(false, true)) { "BACKUP_PREVIEW_CLOSED" }
        try {
            check(!prepared.closing) { "BACKUP_PREVIEW_CLOSED" }
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                AppBackupArchive().write(prepared.archiveFiles, output) { operation.ensureActive() }
            }
        } finally {
            prepared.writing.set(false)
            if (prepared.closing) safeDeleteBackupTree(prepared.directory)
        }
    }

    internal suspend fun prepareRestore(
        uri: Uri,
        credentials: Boolean,
        password: CharArray,
    ): PreparedProfileRestore {
        var result: PreparedProfileRestore? = null
        try {
            return withContext(Dispatchers.IO) {
                val operation = kotlin.coroutines.coroutineContext
                val directory = coordinator.stageDirectory()
                try {
                    val prepared =
                        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            AppBackupRestore(
                                context,
                            ).prepare(input, directory, credentials, password) { operation.ensureActive() }
                        }
                    result = prepared
                    preflightLibrary(prepared)
                    prepared
                } catch (failure: Exception) {
                    safeDeleteBackupTree(directory)
                    throw failure
                }
            }
        } catch (failure: Exception) {
            result?.close()
            throw failure
        } finally {
            password.fill('\u0000')
        }
    }

    internal suspend fun queueRestore(prepared: PreparedProfileRestore) =
        withContext(Dispatchers.IO) {
            preflightLibrary(prepared)
            kotlin.coroutines.coroutineContext.ensureActive()
            coordinator.queue(prepared, storage, ownership)
        }

    private suspend fun preflightLibrary(prepared: PreparedProfileRestore) {
        if (prepared.library.isNotEmpty()) {
            PortableKnowledgeRestore.preflight(
                RestoreLibrarySource(prepared.library, prepared.members),
                MediaStoreDocuments(context, "library"),
                prepared.state
                    .getValue("libraryIncluded")
                    .jsonPrimitive.boolean,
            )
        }
    }
}
