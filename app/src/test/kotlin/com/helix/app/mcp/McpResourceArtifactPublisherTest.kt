package com.helix.app.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.mcp.McpResourceBlob
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class McpResourceArtifactPublisherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val store by lazy {
        WorkspaceArtifactStore(ScopeRootResolver { temporary.root.toPath() }).also { it.ensureLayout("app") }
    }
    private val blob =
        McpResourceBlob(
            "file:///../../private",
            "application/x-malicious",
            "abc".toByteArray(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )

    private fun call(deadline: Instant = Instant.now().plusSeconds(30)) =
        ExecutableToolCall(
            "call",
            "read_mcp_resource",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            deadline,
            NoCancellation,
            "session",
            "turn",
        )

    @Test fun registersVerifiedBytesWithOriginalSessionAndTurnWithoutTrustingRemotePath() {
        val records = mutableListOf<WorkspaceArtifactStore.ArtifactRecord>()
        val publisher =
            McpResourceArtifactPublisher(store, { session, record ->
                assertEquals("session", session)
                assertEquals("turn", record.turnId)
                assertEquals(blob.contentHash, record.sha256)
                assertArrayEquals(
                    blob.bytes,
                    store.openRead(FileScopePath(record.scopeId, record.relativePath)).use { it.readBytes() },
                )
                records += record
            }, "app") { session, turn -> session == "session" && turn == "turn" }
        val first = publisher.publish(call(), blob, 0)
        val second = publisher.publish(call(), blob, 0)
        assertEquals("REGISTERED", first.getValue("status").jsonPrimitive.content)
        assertNotEquals(first["path"], second["path"])
        assertEquals(2, records.size)
        assertFalse(first.toString().contains("/private"))
        assertFalse(first.toString().contains("x-malicious"))
    }

    @Test fun failedRegistrationPreservesSavedFileWithoutInventingArtifact() {
        val publisher =
            McpResourceArtifactPublisher(store, { _, _ -> error("registration failed") }, "app") { _, _ -> true }
        val result = publisher.publish(call(), blob, 0)
        assertEquals("SAVED_NOT_REGISTERED", result.getValue("status").jsonPrimitive.content)
        assertNull(result["artifactRef"])
        val path = FileScopePath.fromModelReference(result.getValue("path").jsonPrimitive.content)
        assertArrayEquals(blob.bytes, store.openRead(path).use { it.readBytes() })
    }

    @Test fun cancellationAfterSaveDoesNotRegisterOrEraseTheFile() {
        val publisher =
            McpResourceArtifactPublisher(store, { _, _ -> fail("must not register") }, "app") { _, _ -> true }
        val cancel =
            object : com.helix.core.model.CancelSignal {
                override fun isCancelled() =
                    temporary.root
                        .resolve("output")
                        .listFiles()!!
                        .isNotEmpty()
            }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            publisher.publish(call().copy(cancel = cancel), blob, 0)
        }
        assertArrayEquals(
            blob.bytes,
            temporary.root
                .resolve("output")
                .listFiles()!!
                .single()
                .readBytes(),
        )
    }

    @Test fun laterQuotaFailureDoesNotEraseAnEarlierArtifact() {
        store.ensureLayout("app")
        val used =
            com.helix.core.workspace.WorkspaceQuota
                .usageBytes(temporary.root.toPath())
        val limited =
            WorkspaceArtifactStore(
                ScopeRootResolver { temporary.root.toPath() },
                com.helix.core.workspace
                    .WorkspaceQuotaPolicy(used + blob.bytes.size),
            )
        var registrations = 0
        val publisher = McpResourceArtifactPublisher(limited, { _, _ -> registrations++ }, "app") { _, _ -> true }
        val saved = publisher.publish(call(), blob, 0)
        assertThrows(com.helix.core.workspace.WorkspaceQuota.QuotaExceeded::class.java) {
            publisher.publish(call(), blob, 1)
        }
        assertEquals(1, registrations)
        val path = FileScopePath.fromModelReference(saved.getValue("path").jsonPrimitive.content)
        assertArrayEquals(blob.bytes, limited.openRead(path).use { it.readBytes() })
    }

    @Test fun expiredAndWrongTurnCannotPublish() {
        val publisher =
            McpResourceArtifactPublisher(store, { _, _ -> fail("must not register") }, "app") { _, _ -> true }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            publisher.publish(call(Instant.EPOCH), blob, 0)
        }
        val wrongTurn =
            McpResourceArtifactPublisher(store, { _, _ -> fail("must not register") }, "app") { _, _ -> false }
        assertThrows(IllegalArgumentException::class.java) { wrongTurn.publish(call(), blob, 0) }
        assertTrue(
            temporary.root
                .resolve("output")
                .listFiles()!!
                .isEmpty(),
        )
    }
}
