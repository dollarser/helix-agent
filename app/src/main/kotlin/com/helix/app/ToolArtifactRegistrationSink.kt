package com.helix.app

import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore

/**
 * The artifact sink for app tool writes (doc 02 §8/§9.2): the write tool publishes the file
 * first, and only then does this sink register the `artifacts` row — re-verifying the file on
 * disk (existence, size, SHA-256) through the repository, so an unverified reference row can
 * never land. The row is stable per (session, path): a re-write of the same path refreshes the
 * existing row (keeping its id so message attachments bound to the artifact survive) and stamps
 * the writing turn from the tool call's trusted context.
 *
 * The stored path is the file's FULL `scope:` reference: the record carries the real scope id
 * the write landed in (not a fixed app scope), so a file written under a selected or other
 * authorized root is registered and later opened against exactly that scope.
 */
internal class ToolArtifactRegistrationSink(
    private val storage: HelixStorage,
    private val resolveWorkspaceFile: (FileScopePath) -> java.io.File,
) : WorkspaceArtifactStore.ArtifactSink {
    override fun register(
        sessionId: String,
        record: WorkspaceArtifactStore.ArtifactRecord,
    ) {
        val scopePath = FileScopePath(record.scopeId, record.relativePath)
        val file = resolveWorkspaceFile(scopePath)
        storage.withTransaction {
            storage.artifacts.registerOrRefresh(
                record.id,
                sessionId,
                scopePath.toModelReference(),
                record.mediaType,
                record.sizeBytes,
                record.sha256,
                record.turnId,
                file,
            )
        }
    }
}
