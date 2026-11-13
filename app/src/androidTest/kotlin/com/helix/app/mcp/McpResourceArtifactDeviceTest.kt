package com.helix.app.mcp

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.ToolArtifactRegistrationSink
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.mcp.McpResourceBlob
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real private files and Room registration/reopen; no external server or model. */
class McpResourceArtifactDeviceTest {
    @Test fun binaryArtifactSurvivesStorageReopenWithOriginalOwnershipAndVerifiedBytes() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val name = "mcp-artifact-${UUID.randomUUID()}.db"
        val root = File(app.filesDir, name).apply { mkdirs() }
        var storage = HelixStorage.open(app, name, File(root, "content"))
        try {
            storage.sessions.create("session", "MCP resource artifact", null, null, 1)
            storage.turns.start("turn", "session", 1)
            val store = WorkspaceArtifactStore(ScopeRootResolver { root.toPath() }).also { it.ensureLayout("test") }
            val sink = ToolArtifactRegistrationSink(storage, store::openRead) { path -> File(root, path.relativePath) }
            val publisher =
                McpResourceArtifactPublisher(store, sink, "test") { session, turn ->
                    storage.turns.resolve(turn).sessionId == session
                }
            val call =
                ExecutableToolCall(
                    "call",
                    "read_mcp_resource",
                    "1",
                    JsonObject(emptyMap()),
                    ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(30),
                    NoCancellation,
                    "session",
                    "turn",
                )
            val bytes = "binary fixture".toByteArray()
            val hash =
                java.security.MessageDigest
                    .getInstance(
                        "SHA-256",
                    ).digest(bytes)
                    .joinToString("") { "%02x".format(it) }
            val result =
                publisher.publish(
                    call,
                    McpResourceBlob("file:///remote/report.pdf", "application/pdf", bytes, hash),
                    0,
                )
            assertEquals("REGISTERED", result.getValue("status").jsonPrimitive.content)
            val id = result.getValue("artifactRef").jsonPrimitive.content
            storage.close()
            storage = HelixStorage.open(app, name, File(root, "content"))
            val artifact = storage.artifacts.resolve(id)
            assertEquals("session", artifact.sessionId)
            assertEquals("turn", artifact.turnId)
            assertEquals(hash, artifact.sha256)
            val path = FileScopePath.fromModelReference(result.getValue("path").jsonPrimitive.content)
            assertEquals(path.toModelReference(), artifact.relativePath)
            assertArrayEquals(bytes, store.openRead(path).use { it.readBytes() })
        } finally {
            storage.close()
            app.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
