package com.helix.app.infrastructure.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.application.agent.GoalRunCoordinator
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TerminalCommitCommand
import com.helix.core.agent.turn.QueuedAdmissionExpectation
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnAdmissionIdentity
import com.helix.core.agent.turn.TurnAdmissionInput
import com.helix.core.agent.turn.TurnAdmissionResult
import com.helix.core.agent.turn.TurnGoalDeliveryInvalidation
import com.helix.core.agent.turn.TurnModelBinding
import com.helix.core.agent.turn.TurnSteeringConfiguration
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.InputConfiguration
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputSpec
import com.helix.core.storage.repository.SessionInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class RoomTurnLifecycleStoreDeviceTest {
    @Test fun queueExpectationsAreRecheckedBeforeAnyTurnOrMessageWrite() =
        fixture { storage, store ->
            val config = InputConfiguration("provider", "model", "CHAT", "accepted-fingerprint")
            storage.sessionInputs.accept(
                SessionInputSpec(
                    "input",
                    "session",
                    SessionInputDelivery.QUEUE,
                    null,
                    2,
                    "accepted text",
                    emptyList(),
                    config,
                    1000,
                ),
            )
            val expected =
                QueuedAdmissionExpectation(
                    "input",
                    2,
                    true,
                    TurnSteeringConfiguration(
                        config.providerId,
                        config.modelId,
                        config.mode,
                        config.fingerprint,
                    ),
                )
            val input = command("turn", "input").copy(queuedInput = expected)
            val invalid =
                listOf(
                    input.copy(queuedInput = expected.copy(revision = 1)),
                    input.copy(
                        queuedInput =
                            expected.copy(
                                configuration = expected.configuration.copy(fingerprint = "changed"),
                            ),
                    ),
                    input.copy(identity = input.identity.copy(sessionId = "other")),
                    input.copy(identity = input.identity.copy(clientRequestId = "different-input")),
                )
            invalid.forEach { rejected ->
                assertEquals(TurnAdmissionResult.FreshRejected, store.admit(rejected))
                assertTrue(storage.turns.listBySession("session").isEmpty())
                assertTrue(storage.messages.listBySession("session").isEmpty())
                assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("input")?.state)
            }
            assertTrue(store.admit(input) is TurnAdmissionResult.Started)
            assertEquals(SessionInputState.APPENDED, storage.sessionInputs.get("input")?.state)
            assertEquals("turn", storage.sessionInputs.get("input")?.consumedTurnId)
        }

    @Test fun nonHeadQueueCannotConsumeEvenWhenRevisionAndConfigurationMatch() =
        fixture { storage, store ->
            val config = InputConfiguration("provider", "model", "CHAT", "same")
            listOf("first", "second").forEachIndexed { index, id ->
                storage.sessionInputs.accept(
                    SessionInputSpec(
                        id,
                        "session",
                        SessionInputDelivery.QUEUE,
                        null,
                        0,
                        id,
                        emptyList(),
                        config,
                        1000L + index,
                    ),
                )
            }
            val input =
                command("turn", "second").copy(
                    queuedInput =
                        QueuedAdmissionExpectation(
                            "second",
                            0,
                            true,
                            TurnSteeringConfiguration("provider", "model", "CHAT", "same"),
                        ),
                )
            assertEquals(TurnAdmissionResult.FreshRejected, store.admit(input))
            assertNull(storage.turns.find("turn"))
            assertEquals("first", storage.sessionInputs.headQueue("session")?.inputId)
        }

    @Test fun nullableSessionModelIdentityIsDistinctFromResolvedExecutionModel() =
        fixture { storage, store ->
            storage.sessions.create("nullable", "Synthetic nullable model", "provider", null, 1000)
            val identity = TurnAdmissionIdentity("nullable", "turn", "call", "request", "fingerprint")
            val input =
                command("turn", "request").copy(
                    identity = identity,
                    expectedSessionModelId = null,
                )
            assertTrue(store.admit(input) is TurnAdmissionResult.Started)
            assertEquals("model", storage.turnRuntimeRecords.resolve("turn").modelId)
            assertNull(storage.sessions.resolve("nullable").modelId)
            assertEquals(TurnState.WAITING_MODEL, store.persistedPhase("turn"))
            assertNull(store.persistedAssistantText("turn"))
        }

    @Test fun terminalPreparationSettlesGoalEditsAndPendingSteerInOneDurablePath() =
        fixture { storage, store ->
            val started = store.admit(command("turn", "request")) as TurnAdmissionResult.Started
            val coordinator = started.turn.coordinator
            kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(2000)
                }
            val goalCoordinator =
                GoalRunCoordinator(
                    storage,
                    clock,
                    { UUID.randomUUID().toString() },
                )
            val goalId = goalCoordinator.create("Synthetic goal", emptyList(), GoalBudgets())
            RoomGoalOwnership(storage).bind(goalId, "session")
            assertEquals(
                1,
                storage.goalControls.stage(
                    goalId,
                    0,
                    "turn",
                    """{"objective":"must remain unapplied"}""",
                ),
            )
            storage.turns.requestPause("turn", 1900)
            storage.sessionInputs.accept(
                SessionInputSpec(
                    "steer",
                    "session",
                    SessionInputDelivery.STEER,
                    "turn",
                    0,
                    "late steer",
                    emptyList(),
                    InputConfiguration("provider", "model", "CHAT", "same"),
                    1000,
                ),
            )
            val outcome = ModelStreamTerminal(TurnState.COMPLETED, null)
            kotlinx.coroutines.runBlocking {
                store.commitTerminal(TerminalCommitCommand(coordinator.terminalCheckpoint(), outcome))
            }
            val receipt = store.prepareTerminalRelease("turn", "session", outcome)
            assertEquals(
                listOf(TurnGoalDeliveryInvalidation("session", goalId)),
                receipt.discardedGoals,
            )
            assertEquals("Synthetic goal", storage.goals.resolve(goalId).objective)
            assertNull(storage.goalControls.find(goalId)?.pendingTurnId)
            assertEquals(SessionInputState.NEEDS_ATTENTION, storage.sessionInputs.get("steer")?.state)
            assertEquals("STEER_TARGET_FINISHED", storage.sessionInputs.get("steer")?.blockedReason)
            assertTrue(store.prepareTerminalRelease("turn", "session", outcome).discardedGoals.isEmpty())
        }

    @Test fun failedReleaseParkingRequiresTerminalOwnerAndKeepsItsOutcome() {
        fixture { storage, store ->
            val admitted = store.admit(command("turn", "request")) as TurnAdmissionResult.Started
            val coordinator = admitted.turn.coordinator
            val config = InputConfiguration("provider", "model", "CHAT", "same")
            listOf("session", "other").forEach { sessionId ->
                storage.sessionInputs.accept(
                    SessionInputSpec(
                        "queue-$sessionId",
                        sessionId,
                        SessionInputDelivery.QUEUE,
                        null,
                        0,
                        "synthetic queued text",
                        emptyList(),
                        config,
                        1000,
                    ),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.parkFailedTerminalDelivery("turn", "session")
            }
            assertEquals(TurnState.WAITING_MODEL, store.persistedPhase("turn"))
            assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("queue-session")?.state)
            kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
            val outcome = ModelStreamTerminal(TurnState.COMPLETED, null)
            kotlinx.coroutines.runBlocking {
                store.commitTerminal(TerminalCommitCommand(coordinator.terminalCheckpoint(), outcome))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.parkFailedTerminalDelivery("turn", "other")
            }
            assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("queue-session")?.state)
            assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("queue-other")?.state)
            store.parkFailedTerminalDelivery("turn", "session")
            assertEquals(TurnState.COMPLETED, store.persistedPhase("turn"))
            val parked = requireNotNull(storage.sessionInputs.get("queue-session"))
            assertEquals(SessionInputState.NEEDS_ATTENTION, parked.state)
            assertEquals("TURN_RELEASE_FAILED", parked.blockedReason)
            assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("queue-other")?.state)
        }
    }

    @Test fun recoveryNoticeStaysOnOriginalTaskAcrossDuplicateAndChildSettlement() {
        fixture { storage, store ->
            val parent = storage.turns.start("parent", "session", 1000)
            storage.turns.updateState(parent, TurnState.INTERRUPTED, 0, 1500, "PROCESS_INTERRUPTED")
            val inspection =
                storage.turns.start(
                    "inspection",
                    "session",
                    1000,
                    clientRequestId = AutomaticRecoveryPolicy.requestId("parent"),
                    inputFingerprint = "synthetic inspection",
                    recoveryFromTurnId = "parent",
                )
            val preparing = storage.turns.updateState(inspection, TurnState.BUILDING_CONTEXT, 0, null, null)
            storage.turns.updateState(preparing, TurnState.FAILED, 0, 1500, "SYNTHETIC_FAILURE")
            val outcome = ModelStreamTerminal(TurnState.FAILED, "SYNTHETIC_FAILURE")
            val receipt = store.prepareTerminalRelease("inspection", "session", outcome)
            assertEquals("parent", receipt.recoveryParentTurnId)
            store.prepareTerminalRelease("inspection", "session", outcome)
            val notices =
                storage.messages.listBySession("session").filter {
                    it.kind ==
                        com.helix.app.application.agent.TurnInterruptionNotice.KIND
                }
            assertEquals(1, notices.size)
            com.helix.app.infrastructure.agent
                .AutomaticRecoverySettlement(
                    storage,
                    object : com.helix.core.model.Clock {
                        override fun now() = java.time.Instant.ofEpochMilli(2000)
                    },
                ).finish("inspection", "RECOVERY_INSPECTION_FINISHED", true)
            assertEquals(
                1,
                storage.messages.listBySession("session").count {
                    it.kind ==
                        com.helix.app.application.agent.TurnInterruptionNotice.KIND
                },
            )
            assertEquals("parent", notices.single().turnId)
            assertEquals("INSPECTION_UNAVAILABLE", storage.messages.readContent(notices.single()))
            assertEquals(TurnState.INTERRUPTED.name, storage.turns.resolve("parent").state)
        }
    }

    private fun command(
        turnId: String,
        requestId: String,
    ) = TurnAdmissionCommand(
        TurnAdmissionIdentity("session", turnId, "call", requestId, "fingerprint"),
        TurnModelBinding("provider", "model", "synthetic snapshot"),
        TurnAdmissionInput("accepted text"),
        RunControlConfig(AgentMode.CHAT, false, TurnBudgets(maxOutputTokens = 128)),
        GoalWakeReason.USER_OPEN,
        "model",
    )

    private fun fixture(block: (HelixStorage, RoomTurnLifecycleStore) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "closed-admission-${UUID.randomUUID()}.db"
        val root = File(context.filesDir, name)
        val storage = HelixStorage.open(context, name, root)
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(2000)
            }
        try {
            com.helix.app.test
                .seedFixtureProvider(storage, "provider", "model")
            storage.sessions.create("session", "Synthetic session", "provider", "model", 1000)
            storage.sessions.create("other", "Synthetic other", "provider", "model", 1000)
            val store =
                RoomTurnLifecycleStore(
                    storage,
                    clock,
                    { UUID.randomUUID().toString() },
                )
            block(storage, store)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
