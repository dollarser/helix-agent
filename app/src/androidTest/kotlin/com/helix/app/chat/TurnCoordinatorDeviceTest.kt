package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.Clock
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.MessageAttachmentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** HXA-039 device fixture for current-call identity and transactional model-visible writes. */
@RunWith(AndroidJUnit4::class)
class TurnCoordinatorDeviceTest {
    @Test
    fun repositoryRejectsTurnStateBypass() {
        val storage = isolatedStorage()
        try {
            storage.sessions.create("session-1", "Transitions", null, null, 1_000L)
            val turn = storage.turns.start("turn-1", "session-1", 1_000L)

            assertThrows(IllegalArgumentException::class.java) {
                storage.turns.updateState(turn, TurnState.RUNNING_TOOL, 1, null, null)
            }
            assertEquals(TurnState.CREATED.name, storage.turns.resolve("turn-1").state)
        } finally {
            storage.close()
        }
    }

    @Test
    fun secondModelFailureTerminalizesSecondCallAndKeepsItsPartialText() {
        val storage = isolatedStorage()
        try {
            storage.sessions.create("session-1", "Coordinator", null, null, 1_000L)
            var nextId = 0
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    FixedClock(2_000L),
                    { "generated-${nextId++}" },
                    TurnStartSpec("session-1", "turn-1", "model-1", "snapshot", "hello"),
                )

            val first = coordinator.beginModelStream()
            first.apply(ModelEvent.ToolCallStarted(0, ToolCallId("tool-1"), "time.now"))
            first.apply(ModelEvent.ToolArgumentsDelta(0, "{}"))
            first.apply(ModelEvent.ToolCallFinished(0))
            coordinator.beginToolBatch(listOf("tool-1"))
            coordinator.commitModelToolStep("""[{"id":"tool-1","name":"time.now","arguments":"{}"}]""")
            coordinator.settleBatchCall("tool-1", sideEffectUnknown = false)
            coordinator.openNextModelCall(
                listOf(TurnMessageDraft(ModelRole.TOOL, ChatHistoryBuilder.KIND_TOOL_RESULT, TOOL_RESULT)),
                "model-2",
            )

            val second = coordinator.beginModelStream()
            second.apply(ModelEvent.TextDelta("partial-second"))
            coordinator.terminalize(ModelStreamTerminal(TurnState.FAILED, "INTERNAL"))

            assertEquals(TurnState.FAILED.name, storage.turns.resolve("turn-1").state)
            assertEquals("COMPLETED", storage.modelCalls.resolve("model-1").state)
            assertEquals("FAILED", storage.modelCalls.resolve("model-2").state)
            val assistantTexts =
                storage.messages
                    .listBySession("session-1")
                    .filter { it.role == ModelRole.ASSISTANT.name && it.kind == ChatHistoryBuilder.KIND_TEXT }
                    .mapNotNull(storage.messages::readContent)
            assertEquals(listOf("partial-second"), assistantTexts)
            assertTrue(
                storage.messages.listBySession("session-1").any { it.kind == ChatHistoryBuilder.KIND_TOOL_RESULT },
            )
        } finally {
            storage.close()
        }
    }

    @Test
    fun startBindsAttachmentsAtomicallyWithTheUserMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = isolatedStorage()
        try {
            val session = storage.sessions.create("session-attach", "Attach", null, null, 1_000L)
            val body = "attachment body\n"
            val file = File(context.cacheDir, "attach-${UUID.randomUUID()}.txt")
            file.writeText(body)
            val hash = FileContentStore.sha256Hex(body.toByteArray(Charsets.UTF_8))
            val artifact =
                storage.artifacts.register(
                    "artifact-attach-1",
                    session.id,
                    "input/attachments/att-1/note.txt",
                    "text/plain",
                    file.length(),
                    hash,
                    file,
                )
            var nextId = 0
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    FixedClock(2_000L),
                    { "generated-${nextId++}" },
                    TurnStartSpec(
                        "session-attach",
                        "turn-attach-1",
                        "model-attach-1",
                        "snapshot",
                        "please read this",
                        attachments =
                            listOf(MessageAttachmentRepository.Binding(artifact.id, "REFERENCE", hash)),
                    ),
                )

            val userMessages =
                storage.messages
                    .listBySession("session-attach")
                    .filter { it.role == ModelRole.USER.name }
            assertEquals(1, userMessages.size)
            val bound = storage.messageAttachments.listByMessage(userMessages.first().id)
            assertEquals(1, bound.size)
            assertEquals(0, bound[0].ordinal)
            assertEquals(artifact.id, bound[0].artifactId)
            assertEquals("REFERENCE", bound[0].purpose)
            assertEquals(hash, bound[0].boundSha256)
            // The bind did not disturb the turn flow: it is waiting on the model.
            assertEquals(TurnState.WAITING_MODEL, coordinator.snapshot().phase)
        } finally {
            storage.close()
        }
    }

    private fun isolatedStorage(): HelixStorage {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        return HelixStorage.open(context, "turn-coordinator-$suffix.db", File(context.filesDir, "turn-$suffix"))
    }

    private companion object {
        const val TOOL_RESULT = """{"id":"tool-1","tool":"time.now","status":"SUCCEEDED","summary":"ok"}"""
    }
}

private class FixedClock(
    private val millis: Long,
) : Clock {
    override fun now(): Instant = Instant.ofEpochMilli(millis)
}
