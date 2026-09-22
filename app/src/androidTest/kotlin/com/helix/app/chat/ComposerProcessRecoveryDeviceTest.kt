package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Host runner edits a normal composer, SIGKILLs MainActivity's PID, and then verifies here. */
class ComposerProcessRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun seedComposerRecovery() =
        runBlocking {
            compose.resetDeterministicUiState()
            val storage = compose.container().storage
            storage.sessions.create(COMPOSER_SESSION, "COMPOSER-RECOVERY", null, null, 1)
            assertTrue(
                storage.composerDrafts.save(
                    ChatSubmission(
                        COMPOSER_SESSION,
                        0,
                        "pending-composer-214",
                        "RECOVER-DRAFT-214",
                    ).toDraftEntity(),
                    null,
                ),
            )

            storage.sessions.create(ACCEPTED_SESSION, "COMPOSER-ACCEPTED", null, null, 2)
            val accepted =
                ChatSubmission(
                    ACCEPTED_SESSION,
                    0,
                    ACCEPTED_REQUEST,
                    ACCEPTED_TEXT,
                )
            assertTrue(storage.composerDrafts.save(accepted.toDraftEntity(), null))
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(10)
                }
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    clock,
                    { UUID.randomUUID().toString() },
                    TurnStartSpec(
                        ACCEPTED_SESSION,
                        "composer-accepted-turn",
                        "composer-accepted-model-call",
                        "fixture",
                        accepted.text,
                        clientRequestId = accepted.clientRequestId,
                        inputFingerprint = TurnInputFingerprint.of(accepted.text, emptyList()),
                    ),
                )
            coordinator.beginModelStream()
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            seedCancellationIntent(storage, clock)
            compose.container().chatService.closeSession()
        }

    @Test fun verifyComposerRecovery() =
        runBlocking {
            val container = compose.container()
            val storage = container.storage
            val draft = container.chatService.loadComposerDraft(COMPOSER_SESSION)
            assertNotNull(draft)
            assertEquals("NORMAL-PROCESS-DRAFT-214", draft!!.text)
            assertTrue(draft.revision > 0)
            assertNull(draft.revisedMessageId)
            assertTrue(storage.messages.listBySession(COMPOSER_SESSION).isEmpty())
            assertTrue(storage.turns.listBySession(COMPOSER_SESSION).isEmpty())

            assertNull(container.chatService.loadComposerDraft(ACCEPTED_SESSION))
            val turns = storage.turns.listBySession(ACCEPTED_SESSION)
            assertEquals(1, turns.size)
            assertEquals("composer-accepted-turn", turns.single().id)
            val messages = storage.messages.listBySession(ACCEPTED_SESSION)
            assertEquals(1, messages.size)
            assertEquals(ACCEPTED_TEXT, storage.messages.readContent(messages.single()))
            val accepted =
                ChatSubmission(
                    ACCEPTED_SESSION,
                    0,
                    ACCEPTED_REQUEST,
                    ACCEPTED_TEXT,
                )
            val receipt = container.chatService.acceptedComposerReceipt(accepted)
            assertNotNull(receipt)
            assertEquals(
                "composer-accepted-turn",
                (receipt!!.outcome as ChatSubmissionOutcome.Accepted).turnId,
            )
            verifyCancellationRecovery(storage)
        }

    /** Seed the durable stop boundary; actual UI Stop behavior is covered by the live stop tests. */
    private fun seedCancellationIntent(
        storage: HelixStorage,
        clock: Clock,
    ) {
        storage.sessions.create(CANCEL_SESSION, "COMPOSER-CANCELLING", null, null, 3)
        val turn =
            TurnCoordinator.start(
                storage,
                clock,
                { UUID.randomUUID().toString() },
                TurnStartSpec(CANCEL_SESSION, CANCEL_TURN, CANCEL_MODEL, "fixture", "Seeded cancellation boundary"),
            )
        turn.beginModelStream()
        turn.beginToolBatch(listOf(RUNNING_CALL, PENDING_CALL))
        turn.commitModelToolStep(
            """[{"id":"$RUNNING_CALL","name":"time.now","arguments":"{}"},""" +
                """{"id":"$PENDING_CALL","name":"time.now","arguments":"{}"}]""",
        )
        storage.withTransaction {
            storage.toolCalls.append(RUNNING_CALL, CANCEL_TURN, RUNNING_CALL, "time.now", "1", "{}", "RUNNING")
            storage.toolCalls.append(PENDING_CALL, CANCEL_TURN, PENDING_CALL, "time.now", "1", "{}", "PENDING")
            storage.executions.register(CANCEL_EXECUTION, RUNNING_CALL, "fixture", "{}")
            val current = storage.turns.resolve(CANCEL_TURN)
            storage.turns.updateState(current, TurnState.CANCELLING, current.stepCount, null, null)
        }
        assertEquals(TurnState.CANCELLING.name, storage.turns.resolve(CANCEL_TURN).state)
        File(compose.activity.filesDir, "composer-cancellation-seed.json").writeText(
            buildJsonObject {
                put("turnId", CANCEL_TURN)
                put("state", "CANCELLING")
                put("runningCall", RUNNING_CALL)
                put("pendingCall", PENDING_CALL)
                put("executionId", CANCEL_EXECUTION)
                put("seededBoundary", true)
            }.toString(),
        )
    }

    private fun verifyCancellationRecovery(storage: HelixStorage) {
        // No explicit recovery precedes these checks: normal application startup must have done it.
        val turn = storage.turns.resolve(CANCEL_TURN)
        assertEquals(TurnState.INTERRUPTED.name, turn.state)
        assertEquals(listOf(CANCEL_TURN), storage.turns.listBySession(CANCEL_SESSION).map { it.id })
        val calls = storage.toolCalls.listByTurn(CANCEL_TURN)
        assertEquals(setOf(RUNNING_CALL, PENDING_CALL), calls.map { it.id }.toSet())
        assertTrue(calls.all { it.state == ToolCallState.INTERRUPTED.name })
        assertTrue(calls.all { storage.toolResults.byToolCall(it.id) == null })
        assertNull(storage.executions.byToolCall(PENDING_CALL))
        val execution = storage.executions.byToolCall(RUNNING_CALL)
        assertEquals(CANCEL_EXECUTION, execution?.id)
        assertNull(execution?.exitCode)
        assertNull(execution?.signal)
        val models = storage.modelCalls.listByTurn(CANCEL_TURN)
        assertEquals(listOf(CANCEL_MODEL), models.map { it.id })
        assertEquals("COMPLETED", models.single().state)
        val audit = storage.auditEvents.listByCorrelation(CANCEL_SESSION)
        assertEquals(2, audit.size)
        val interrupted = audit.single { it.type == "recovery.turn_interrupted" }
        assertTrue(interrupted.redactedPayload.contains("\"uncertainToolCall\":\"$RUNNING_CALL\""))
        assertTrue(audit.single { it.type == "recovery.tool_calls_parked" }.redactedPayload.contains(PENDING_CALL))
        repeat(2) {
            val report = RecoveryCoordinatorApp(storage, SystemClock()).recover()
            assertTrue(report.interruptedTurns.isEmpty())
            assertTrue(report.parkedToolCalls.isEmpty())
        }
        assertEquals(turn, storage.turns.resolve(CANCEL_TURN))
        assertEquals(calls, storage.toolCalls.listByTurn(CANCEL_TURN))
        assertEquals(models, storage.modelCalls.listByTurn(CANCEL_TURN))
        assertEquals(execution, storage.executions.byToolCall(RUNNING_CALL))
        assertEquals(audit, storage.auditEvents.listByCorrelation(CANCEL_SESSION))
        File(compose.activity.filesDir, "composer-cancellation-verified.json").writeText(
            buildJsonObject {
                put("turnId", CANCEL_TURN)
                put("state", turn.state)
                put("calls", calls.size)
                put("newExecutions", 0)
                put("newModelCalls", 0)
                put("uncertainCall", RUNNING_CALL)
                put("repeatedRecoveryUnchanged", true)
            }.toString(),
        )
    }

    private companion object {
        const val COMPOSER_SESSION = "composer-recovery"
        const val ACCEPTED_SESSION = "composer-accepted"
        const val ACCEPTED_REQUEST = "accepted-composer-214"
        const val ACCEPTED_TEXT = "ACCEPTED-COMPOSER-214"
        const val CANCEL_SESSION = "composer-cancellation"
        const val CANCEL_TURN = "composer-cancellation-turn"
        const val CANCEL_MODEL = "composer-cancellation-model"
        const val RUNNING_CALL = "composer-cancellation-running"
        const val PENDING_CALL = "composer-cancellation-pending"
        const val CANCEL_EXECUTION = "composer-cancellation-execution"
    }
}
