package com.helix.app.vision

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.VisualArtifact
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolVisualPreparation
import com.helix.tools.framework.VisualPreparationException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class WorkspaceToolImagePublisherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val input = byteArrayOf(1, 2, 3)
    private val visual = VisualArtifact("image", "a".repeat(64), "image/png", 3, 1, 1)
    private val records = mutableListOf<Pair<String, WorkspaceArtifactStore.ArtifactRecord>>()
    private val store by lazy { WorkspaceArtifactStore(ScopeRootResolver { temporary.root.toPath() }) }
    private val call =
        ExecutableToolCall(
            "call",
            "ui.screenshot",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
            "session",
            "turn",
        )

    private fun publisher(preparation: ToolVisualPreparation) =
        WorkspaceToolImagePublisher(
            store,
            { session, record -> records += session to record },
            "workspace",
            preparation,
            ownsTurn = { session, turn -> session == "session" && turn == "turn" },
        )

    @Test fun screenshotFileAndHashExistBeforeOriginalTurnReceivesPixels() {
        val result =
            publisher { original, reference, hash ->
                assertSame(call, original)
                assertEquals("session", records.single().first)
                assertEquals("turn", records.single().second.turnId)
                assertEquals(records.single().second.sha256, hash)
                val bytes = store.openRead(FileScopePath.fromModelReference(reference)).use { it.readBytes() }
                assertArrayEquals(input, bytes)
                visual
            }.publish(call, input, null)
        assertSame(visual, result.visual)
        assertEquals(records.single().second.sha256, result.sha256)
        assertTrue(result.note.isEmpty())
    }

    @Test fun missingVisionKeepsAnHonestFileArtifactWithoutFakePixels() {
        val unavailable = publisher { _, _, _ -> throw VisualPreparationException("VISION_UNAVAILABLE") }
        val result = unavailable.publish(call, input, null)
        assertNull(result.visual)
        assertTrue(result.note.contains("VISION_UNAVAILABLE"))
        val bytes = store.openRead(FileScopePath.fromModelReference(result.reference)).use { it.readBytes() }
        assertArrayEquals(input, bytes)
        assertEquals(1, records.size)
    }

    @Test fun aUserFileAtTheOutputDirectoryIsNeverReplaced() {
        val existing = temporary.newFile("output").apply { writeText("keep") }
        val publisher = publisher { _, _, _ -> error("Must not prepare") }
        assertThrows(com.helix.core.workspace.SymlinkEscapesRoot::class.java) { publisher.publish(call, input, null) }
        assertEquals("keep", existing.readText())
        assertTrue(records.isEmpty())
    }

    @Test fun publicationReusesTheDirectoryWithoutReplacingEarlierScreenshots() {
        val publisher = publisher { _, _, _ -> visual }
        val first = publisher.publish(call, input, null)
        val second = publisher.publish(call, input, null)
        assertTrue(first.reference != second.reference)
        assertEquals(2, records.size)
        val path = FileScopePath.fromModelReference(first.reference)
        assertArrayEquals(input, store.openRead(path).use { it.readBytes() })
    }

    @Test fun publicationCarriesTheAcquisitionScopeRatherThanInferringANewerGrant() {
        var registeredScope: String? = null
        var registeredVisual: VisualArtifact? = null
        val publisher =
            WorkspaceToolImagePublisher(
                store,
                { session, record -> records += session to record },
                "workspace",
                { _, _, _ -> visual },
                registerScreenImage = { original, prepared, acquisitionScope ->
                    assertSame(call, original)
                    registeredVisual = prepared
                    registeredScope = acquisitionScope
                },
                ownsTurn = { _, _ -> true },
            )
        publisher.publish(call, input, "original-acquisition-scope")
        assertEquals("original-acquisition-scope", registeredScope)
        assertSame(visual, registeredVisual)
    }

    @Test fun wrongOwnershipAndExpiredCallsNeverPublishFiles() {
        val publisher = publisher { _, _, _ -> error("Must not prepare") }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { publisher.publish(call.copy(turnId = "other"), input, null) }
        assertThrows(VisualPreparationException::class.java) {
            publisher.publish(call.copy(deadline = Instant.EPOCH), input, null)
        }
        assertTrue(records.isEmpty())
        assertNotNull(temporary.root.listFiles())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
