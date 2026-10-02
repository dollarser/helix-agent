package com.helix.app.vision

import com.helix.core.model.VisionLimits
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.PublishedToolImage
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolVisualPreparation
import com.helix.tools.framework.VisualPreparationException
import java.time.Instant
import java.util.UUID

/** All trusted screen producers share the same file-first artifact registration and visual preparation. */
internal class WorkspaceToolImagePublisher(
    private val workspace: WorkspaceArtifactStore,
    private val sink: WorkspaceArtifactStore.ArtifactSink,
    private val scopeId: String,
    private val preparation: ToolVisualPreparation,
    private val registerScreenImage: (ExecutableToolCall, com.helix.core.model.VisualArtifact, String) -> Unit =
        { _, _, _ -> },
    private val ownsTurn: (String, String) -> Boolean,
) : ToolImagePublication {
    @Suppress("SwallowedException") // An existing directory is expected; writeArtifact still rejects files/symlinks.
    private fun ensureOutputDirectory() {
        try {
            workspace.mkdir(FileScopePath(scopeId, WorkspaceLayout.OUTPUT), region = null)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Do not delete or replace an existing user entry to make publication succeed.
        }
    }

    override fun publish(
        call: ExecutableToolCall,
        png: ByteArray,
        acquisitionScopeRef: String?,
    ): PublishedToolImage {
        val session = requireNotNull(call.sessionId) { "SESSION_REQUIRED" }
        val turn = requireNotNull(call.turnId) { "TURN_REQUIRED" }
        require(ownsTurn(session, turn)) { "TURN_SESSION_MISMATCH" }
        require(png.isNotEmpty() && png.size.toLong() <= VisionLimits.MAX_INPUT_BYTES) { "IMAGE_SIZE_INVALID" }
        if (call.cancel.isCancelled() || !Instant.now().isBefore(call.deadline)) {
            throw VisualPreparationException("CANCELLED")
        }
        ensureOutputDirectory()
        val destination = FileScopePath(scopeId, "output/mobile-screen-${UUID.randomUUID()}.png")
        val written =
            workspace.writeArtifact(
                destination,
                png,
                WorkspaceLayout.OUTPUT,
                sessionId = session,
                sink = sink,
                turnId = call.turnId,
            )
        var note = ""
        val visual =
            try {
                preparation.prepare(call, destination.toModelReference(), written.record.sha256)
            } catch (failure: VisualPreparationException) {
                note = "Screenshot saved; pixels not delivered: ${failure.code}."
                null
            }
        if (visual != null && acquisitionScopeRef != null) registerScreenImage(call, visual, acquisitionScopeRef)
        return PublishedToolImage(
            destination.toModelReference(),
            written.record.sha256,
            written.record.sizeBytes,
            visual,
            note,
        )
    }
}
