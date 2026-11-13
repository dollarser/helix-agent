package com.helix.app.mcp

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.extensions.mcp.McpResourceBlob
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** Remote URI/MIME never choose a local destination or grant image disclosure to a model. */
internal class McpResourceArtifactPublisher(
    private val workspace: WorkspaceArtifactStore,
    private val sink: WorkspaceArtifactStore.ArtifactSink,
    private val scopeId: String,
    private val ownsTurn: (String, String) -> Boolean,
) {
    @Suppress("TooGenericExceptionCaught") // File publication survives a failed registration; report partial status.
    fun publish(
        call: ExecutableToolCall,
        blob: McpResourceBlob,
        index: Int,
    ): JsonObject {
        val session = requireNotNull(call.sessionId) { "SESSION_REQUIRED" }
        val turn = requireNotNull(call.turnId) { "TURN_REQUIRED" }
        require(ownsTurn(session, turn)) { "TURN_SESSION_MISMATCH" }
        checkActive(call)
        val path = FileScopePath(scopeId, "${WorkspaceLayout.OUTPUT}/mcp-resource-${UUID.randomUUID()}-$index.bin")
        val written =
            workspace.writeArtifact(
                path,
                blob.bytes,
                WorkspaceLayout.OUTPUT,
                sessionId = session,
                turnId = turn,
            )
        check(written.record.sha256 == blob.contentHash) { "MCP_RESOURCE_HASH_MISMATCH" }
        checkActive(call)
        val registered =
            try {
                sink.register(session, written.record)
                true
            } catch (failure: java.util.concurrent.CancellationException) {
                throw failure
            } catch (failure: InterruptedException) {
                throw failure
            } catch (_: Exception) {
                false
            }
        // A registration failure cannot roll back a published file. Return its verified recovery
        // reference without inventing an artifact ID; the caller can use the normal file tools.
        return buildJsonObject {
            put("status", if (registered) "REGISTERED" else "SAVED_NOT_REGISTERED")
            if (registered) put("artifactRef", written.record.id)
            put("path", path.toModelReference())
            put("sha256", written.record.sha256)
            put("size", written.record.sizeBytes)
            put("mediaType", written.record.mediaType)
            if (!registered) put("error", "ARTIFACT_REGISTRATION_FAILED")
        }
    }

    private fun checkActive(call: ExecutableToolCall) {
        if (call.cancel.isCancelled() || !Instant.now().isBefore(call.deadline)) {
            throw java.util.concurrent.CancellationException("MCP_RESOURCE_PUBLICATION_CANCELLED")
        }
    }
}
