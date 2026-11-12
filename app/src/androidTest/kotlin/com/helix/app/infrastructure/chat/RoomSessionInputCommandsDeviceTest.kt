package com.helix.app.infrastructure.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.infrastructure.agent.RoomSessionWorkStore
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.ConversationReferenceManifest
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.model.input.InputAttachment
import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.InputRecoveryClaim
import com.helix.core.model.input.InputSkillActivation
import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.RoomInputRecoveryStore
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.SessionInputState
import com.helix.feature.conversation.application.input.InputAcceptance
import com.helix.feature.conversation.application.input.InputDeliveryKey
import com.helix.feature.conversation.application.recovery.AutomaticInputRecovery
import com.helix.feature.conversation.application.recovery.GoalContinuationHandoff
import com.helix.feature.conversation.application.recovery.SessionWorkScheduler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Exercises the actual closed Room write sets, including rollback after the first write succeeds. */
class RoomSessionInputCommandsDeviceTest {
    @Test fun titleFailureRollsBackAcceptedInputAndRetryCanStillAcceptIt() =
        fixture { storage, commands, _ ->
            assertThrows(IllegalArgumentException::class.java) { commands.acceptAndName(spec(), " ") }
            assertNull(commands.receipt("input"))
            assertEquals("", storage.sessions.resolve("session").title)
            val accepted = commands.acceptAndName(spec(), "Initial title") as InputAcceptance.Accepted
            assertFalse(accepted.duplicate)
            assertEquals("Initial title", storage.sessions.resolve("session").title)
            val duplicate = commands.acceptAndName(spec(), "Replacement title") as InputAcceptance.Accepted
            assertTrue(duplicate.duplicate)
            assertEquals(accepted.receipt, duplicate.receipt)
            assertEquals("Initial title", storage.sessions.resolve("session").title)
            assertTrue(commands.acceptAndName(spec().copy(text = "different"), "Other") is InputAcceptance.Rejected)
            assertEquals("Initial title", storage.sessions.resolve("session").title)
        }

    @Test fun editParksAtomicallyAndPreservesEveryAcceptedBinding() =
        fixture { storage, commands, directory ->
            val body = File(directory, "attachment.txt").apply { writeText("immutable attachment") }
            val hash = FileContentStore.sha256Hex(body)
            storage.artifacts.register(
                "artifact",
                "session",
                "input/attachment.txt",
                "text/plain",
                body.length(),
                hash,
                body,
            )
            val reference =
                ConversationReferenceSnapshotInput(
                    "source",
                    "Source",
                    ConversationReferenceKind.HISTORY,
                    listOf("message"),
                    ConversationReferenceManifest(1, "a".repeat(64), 1, 40).encode(),
                )
            val request =
                spec().copy(
                    attachments = listOf(InputAttachment("artifact", hash)),
                    reference = reference,
                    skillActivations =
                        listOf(
                            InputSkillActivation("PROJECT", "review", "b".repeat(64), "original args"),
                        ),
                    knowledgeReferences =
                        listOf(
                            KnowledgeReference("NOTE", "note", "Original title", "c".repeat(64), 20),
                        ),
                )
            val accepted = (commands.acceptAndName(request, "Title") as InputAcceptance.Accepted).receipt
            val original = requireNotNull(storage.sessionInputs.get("input"))
            assertTrue(commands.editTextAndPark(accepted.key, "edited text", 2))
            val edited = requireNotNull(storage.sessionInputs.get("input"))
            assertEquals(SessionInputState.NEEDS_ATTENTION, edited.state)
            assertEquals("INPUT_EDITED", edited.blockedReason)
            assertEquals(original.revision + 2, edited.revision)
            assertEquals("edited text", commands.readText("input"))
            assertEquals(original.inputId, edited.inputId)
            assertEquals(original.sessionId, edited.sessionId)
            assertEquals(original.sequence, edited.sequence)
            assertEquals(original.delivery, edited.delivery)
            assertEquals(original.expectedTurnId, edited.expectedTurnId)
            assertEquals(original.configuration, edited.configuration)
            assertEquals(original.attachments, edited.attachments)
            assertEquals(original.reference, edited.reference)
            assertEquals(reference, storage.sessionInputs.readReference(edited))
            assertEquals(original.skillActivations, edited.skillActivations)
            assertEquals(original.knowledgeReferences, edited.knowledgeReferences)
            assertEquals("immutable attachment", body.readText())
            assertFalse(commands.editTextAndPark(accepted.key, "stale edit", 3))
            assertFalse(commands.resume(accepted.key, 3))
            assertTrue(commands.resume(requireNotNull(commands.receipt("input")).key, 3))
            assertEquals(SessionInputState.PENDING, storage.sessionInputs.get("input")?.state)
        }

    @Test fun clearingQueuedTextRetainsExactSkillIdentityAndParksForRevalidation() =
        fixture { storage, commands, _ ->
            val skill = InputSkillActivation("USER_IMPORTED", "guide", "a".repeat(64), "")
            val request = spec().copy(skillActivations = listOf(skill))
            val accepted = (commands.acceptAndName(request, "Title") as InputAcceptance.Accepted).receipt
            assertTrue(commands.editTextAndPark(accepted.key, "", 2))
            val edited = requireNotNull(storage.sessionInputs.get("input"))
            assertEquals("", commands.readText("input"))
            assertEquals(listOf(skill), edited.skillActivations)
            assertEquals(request.configuration, edited.configuration)
            assertEquals(SessionInputState.NEEDS_ATTENTION, edited.state)
            assertEquals("INPUT_EDITED", edited.blockedReason)
            assertFalse(commands.editTextAndPark(accepted.key, "stale edit", 3))
            assertEquals(edited, storage.sessionInputs.get("input"))
        }

    @Test fun failedParkRollsBackTheAlreadyEditedRowAndBytes() =
        fixture { storage, commands, _ ->
            val accepted =
                (
                    commands.acceptAndName(
                        spec().copy(revision = Long.MAX_VALUE - 1),
                        "Title",
                    ) as InputAcceptance.Accepted
                ).receipt
            val original = storage.sessionInputs.get("input")
            // edit reaches MAX_VALUE; the second mutation cannot increment it and the whole command rolls back.
            assertThrows(
                ArithmeticException::class.java,
            ) { commands.editTextAndPark(accepted.key, "must roll back", 2) }
            assertEquals(original, storage.sessionInputs.get("input"))
            assertEquals("original text", commands.readText("input"))
        }

    @Test fun sessionIdentityAndRevisionAreCheckedForEveryMutation() =
        fixture { storage, commands, _ ->
            val accepted = (commands.acceptAndName(spec(), "Title") as InputAcceptance.Accepted).receipt
            val original = storage.sessionInputs.get("input")
            for (key in listOf(accepted.key.copy(sessionId = "other"), accepted.key.copy(revision = 99))) {
                assertFalse(commands.editTextAndPark(key, "wrong owner", 2))
                assertFalse(commands.withdraw(key, 2))
                assertFalse(commands.park(key, "USER_STOP", 2))
                assertFalse(commands.resume(key, 2))
                assertFalse(commands.steer(key, "target", 2))
                assertEquals(original, storage.sessionInputs.get("input"))
            }
            assertFalse(commands.editTextAndPark(accepted.key, " ", 2))
            assertTrue(commands.withdraw(accepted.key, 2))
            assertFalse(commands.withdraw(accepted.key, 3))
            assertTrue(commands.pendingViews("session").isEmpty())
            assertFalse(commands.hasPending("session"))
        }

    @Test fun promotionChecksTargetOwnerLivenessAndPreservesAcceptedIdentity() =
        fixture { storage, commands, _ ->
            val accepted = (commands.acceptAndName(spec(), "Title") as InputAcceptance.Accepted).receipt
            val original = requireNotNull(storage.sessionInputs.get("input"))
            storage.turns.start("other-target", "other", 1)
            val ended = storage.turns.start("ended", "session", 1)
            storage.turns.updateState(ended, TurnState.INTERRUPTED, 0, 2, null)
            assertFalse(commands.steer(accepted.key, "missing", 2))
            assertFalse(commands.steer(accepted.key, "other-target", 2))
            assertFalse(commands.steer(accepted.key, "ended", 2))
            assertEquals(original, storage.sessionInputs.get("input"))
            storage.turns.start("live", "session", 2)
            assertTrue(commands.steer(accepted.key, "live", 3))
            assertFalse(commands.steer(accepted.key, "live", 3))
            val promoted = requireNotNull(storage.sessionInputs.get("input"))
            assertEquals(SessionInputDelivery.STEER, promoted.delivery)
            assertEquals("live", promoted.expectedTurnId)
            assertEquals(original.textRef, promoted.textRef)
            assertEquals(original.configuration, promoted.configuration)
            assertEquals(original.sequence, promoted.sequence)
            assertEquals(original.revision + 1, promoted.revision)
            val deliveryStore = RoomSessionInputDeliveryStore(storage)
            assertNull(deliveryStore.load(accepted.key))
            val promotedKey = requireNotNull(commands.receipt("input")).key
            assertEquals("live", requireNotNull(deliveryStore.load(promotedKey)).expectedTurnId)
            storage.turns.updateState(storage.turns.resolve("live"), TurnState.INTERRUPTED, 0, 4, null)
            assertTrue(storage.sessionInputs.requeueAfterTargetFinished("input", promotedKey.revision, 5))
            assertNull(deliveryStore.load(promotedKey))
            val requeued = requireNotNull(deliveryStore.load(requireNotNull(commands.receipt("input")).key))
            assertEquals(SessionInputDelivery.QUEUE, requeued.delivery)
            assertNull(requeued.expectedTurnId)
            assertEquals(promotedKey.revision + 1, requeued.key.revision)
        }

    @Test fun archivedSessionsCannotAcceptResumeOrPromoteInputs() =
        fixture { storage, commands, _ ->
            val accepted = (commands.acceptAndName(spec(), "Title") as InputAcceptance.Accepted).receipt
            storage.turns.start("live", "session", 1)
            storage.sessions.archive("session", 2)
            assertFalse(commands.steer(accepted.key, "live", 3))
            assertTrue(commands.acceptAndName(spec().copy(inputId = "second"), "Other") is InputAcceptance.Rejected)
            assertNull(commands.receipt("second"))
            assertTrue(commands.park(accepted.key, "USER_STOP", 3))
            val parked = requireNotNull(commands.receipt("input"))
            assertFalse(commands.resume(parked.key, 4))
            assertTrue(commands.sessionsWithPending().isEmpty())
            // Withdrawal remains allowed without reviving the archived session.
            assertTrue(commands.withdraw(parked.key, 4))
        }

    @Test fun realEditDuringRecoveryStaysParkedAcrossSecondRecoveryAndDrain() =
        fixture { storage, commands, _ ->
            val accepted = (commands.acceptAndName(spec(), "Title") as InputAcceptance.Accepted).receipt
            assertTrue(commands.park(accepted.key, "PROCESS_INTERRUPTED", 2))
            val executionGate = Any()
            val recoveryStore = RoomInputRecoveryStore(storage, executionGate)
            val candidate = recoveryStore.candidates("session").single()
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(4)
                }
            var validations = 0
            val recovery =
                AutomaticInputRecovery(
                    recoveryStore,
                    clock,
                    { claimed ->
                        validations++
                        assertEquals(candidate, claimed)
                        // Exercise the actual edit command at an adversarial durable boundary. ChatService
                        // additionally serializes the user entry point and recovery with submissionGate.
                        assertTrue(
                            commands.editTextAndPark(
                                InputDeliveryKey(claimed.inputId, claimed.sessionId, claimed.revision),
                                "user edited text",
                                3,
                            ),
                        )
                        true
                    },
                    { "Recovery ended" },
                )
            runBlocking { recovery.recover("session") }
            val edited = requireNotNull(storage.sessionInputs.get("input"))
            assertEquals(candidate.revision + 2, edited.revision)
            assertEquals(SessionInputState.NEEDS_ATTENTION, edited.state)
            assertEquals("INPUT_EDITED", edited.blockedReason)
            assertFalse(recoveryStore.resume(candidate, 4))
            assertFalse(recoveryStore.fail(candidate, "INPUT_RECOVERY_UNAVAILABLE", "Must not appear", 4))
            assertTrue(storage.auditEvents.find("input-recovery:input") != null)
            assertTrue(recoveryStore.candidates("session").isEmpty())
            assertEquals(
                InputRecoveryClaim.Unavailable,
                recoveryStore.claim(candidate.copy(revision = edited.revision, blockedReason = "INPUT_EDITED"), 4),
            )
            runBlocking { recovery.recover("session") }
            val drain = observeDrain(storage, clock, executionGate, recovery)
            assertEquals(1, validations)
            assertEquals(1, drain.completions)
            assertEquals(0, drain.deliveries)
            assertTrue(drain.failures.isEmpty())
            assertEquals(edited, storage.sessionInputs.get("input"))
            assertEquals("user edited text", commands.readText("input"))
            assertTrue(storage.messages.listBySession("session").isEmpty())
            assertNull(storage.auditEvents.find("input-recovery-ended:input"))
        }

    private data class DrainObservation(
        val completions: Int,
        val deliveries: Int,
        val failures: List<Exception>,
    )

    private fun observeDrain(
        storage: HelixStorage,
        clock: Clock,
        executionGate: Any,
        recovery: AutomaticInputRecovery,
    ): DrainObservation {
        var deliveries = 0
        var drainCompletions = 0
        val failures = mutableListOf<Exception>()
        runBlocking {
            coroutineScope {
                val scheduler =
                    SessionWorkScheduler(
                        store = RoomSessionWorkStore(storage),
                        clock = clock,
                        scope = this,
                        submissionGate = Mutex(),
                        turnGate = executionGate,
                        hasLiveTurn = { false },
                        goalContinuation = NoGoalContinuation,
                        consumeQueueInput = { deliveries++ },
                        submitContinuation = { error("Edited input must not yield a Goal continuation") },
                        refreshProjection = { drainCompletions++ },
                        reportFailure = { failures += it },
                        recoverInputs = recovery::recover,
                    )
                scheduler.requestDrain("session")
            }
        }
        return DrainObservation(drainCompletions, deliveries, failures)
    }

    private object NoGoalContinuation : GoalContinuationHandoff {
        override fun disarm(sessionId: String) = Unit

        override fun reserveUserHandoff(
            sessionId: String,
            turnId: String,
        ) = error("Unexpected handoff")

        override fun reserveEligibleHandoff(
            sessionId: String,
            ownerTurnId: String,
        ) = error("Unexpected handoff")

        override fun handoffOwner(sessionId: String): String? = null

        override fun resumeEligible(sessionId: String): SubmitTurnCommand? =
            error("Parked input blocks Goal continuation")

        override fun finishHandoff(
            sessionId: String,
            previousTurnId: String,
        ) = error("Unexpected handoff")
    }

    private fun spec() =
        SessionInputSpec(
            "input",
            "session",
            SessionInputDelivery.QUEUE,
            null,
            0,
            "original text",
            emptyList(),
            InputConfiguration("provider", "model", "ACT", "configuration-fingerprint"),
            1,
        )

    private fun fixture(block: (HelixStorage, RoomSessionInputCommands, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "input-commands-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(directory, "content"))
        try {
            storage.sessions.create("session", "", null, null, 0)
            storage.sessions.create("other", "Other", null, null, 0)
            block(storage, RoomSessionInputCommands(storage), directory)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }
}
