package com.helix.app.documents

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.DocumentSnapshotEntity
import com.helix.core.storage.repository.DocumentSnapshotRepository
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.documents.DocumentException
import com.helix.extensions.documents.DocumentLimits
import com.helix.extensions.documents.DocumentParser
import com.helix.extensions.documents.DocumentResult
import com.helix.extensions.documents.DocumentSnapshot
import com.helix.extensions.documents.DocumentSnapshots
import com.helix.tools.framework.ExecutableToolCall
import java.io.File

/** Composition only: durable facts and binary lifecycle belong to storage. */
internal class RoomDocumentSnapshots(
    storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
) : DocumentSnapshots {
    private val index = storage.documentSnapshots
    private val repository =
        DocumentSnapshotRepository(
            index,
            storage.contentStore as FileContentStore,
        ) { write -> storage.withTransaction(write) }

    override fun create(
        call: ExecutableToolCall,
        path: String,
    ): DocumentSnapshot =
        guarded {
            snapshot(repository.create(session(call), path, ::open) { DocumentLimits.checkActive(call) })
        }

    override fun resolve(
        call: ExecutableToolCall,
        reference: String,
    ): DocumentSnapshot =
        guarded {
            snapshot(repository.resolve(session(call), reference, ::open) { DocumentLimits.checkActive(call) })
        }

    fun sourcePath(
        session: String,
        reference: String,
    ): String? = index.find(session, reference)?.sourcePath

    fun file(
        call: ExecutableToolCall,
        snapshot: DocumentSnapshot,
    ): File =
        guarded {
            repository.file(session(call), snapshot.reference) { DocumentLimits.checkActive(call) }
        }

    private fun session(call: ExecutableToolCall) = call.sessionId ?: throw DocumentException("SCOPE_DENIED")

    private fun open(path: String) = workspace.openRead(FileScopePath.fromModelReference(path))

    private fun snapshot(entity: DocumentSnapshotEntity) =
        DocumentSnapshot(entity.id, entity.sourcePath, ContentRef.parse(entity.contentRef).sha256, entity.format)

    private fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (failure: DocumentSnapshotRepository.Unavailable) {
            throw DocumentException(failure.code).apply { initCause(failure) }
        } catch (_: SecurityException) {
            throw DocumentException("SCOPE_DENIED")
        }
}
