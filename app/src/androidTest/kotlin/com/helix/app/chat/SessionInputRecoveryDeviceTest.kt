package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.agent.TurnSteeringDraft
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.InputConfiguration
import com.helix.core.storage.repository.SessionInputAcceptResult
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputSpec
import com.helix.core.storage.repository.SessionInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Repository reopen coverage only; this does not substitute for normal-process SIGKILL journeys. */
@RunWith(AndroidJUnit4::class)
class SessionInputRecoveryDeviceTest {
    @Test
    fun pendingInputIsParkedWithoutCreatingHistory() = recoverBoundary(appended = false, requested = false)

    @Test
    fun appendedInputWithoutRequestKeepsItsSingleMessageAndNoRequestReceipt() =
        recoverBoundary(appended = true, requested = false)

    @Test
    fun requestInFlightKeepsItsLocalReceiptWithoutCreatingAnotherCall() =
        recoverBoundary(appended = true, requested = true)

    @Test
    fun cancellationRecoveryPreservesParkedInputWithoutReplay() =
        recoverBoundary(appended = false, requested = false, cancelling = true)

    private fun recoverBoundary(
        appended: Boolean,
        requested: Boolean,
        cancelling: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val database = "input-recovery-$suffix.db"
        val content = File(context.filesDir, "input-recovery-$suffix")
        var storage = HelixStorage.open(context, database, content)
        try {
            seedBoundary(storage, appended, requested, cancelling)
            val messagesBefore = storage.messages.listBySession("session")
            val callsBefore = storage.modelCalls.listByTurn("turn").map { it.id }
            val inputBefore = requireNotNull(storage.sessionInputs.get("input"))
            storage.close()
            storage = HelixStorage.open(context, database, content)
            val report = RecoveryCoordinatorApp(storage, SystemClock()).recover()
            assertTrue(report.interruptedTurns.containsKey("turn"))
            assertEquals(TurnState.INTERRUPTED.name, storage.turns.resolve("turn").state)
            assertEquals(messagesBefore, storage.messages.listBySession("session"))
            assertEquals(callsBefore, storage.modelCalls.listByTurn("turn").map { it.id })
            val inputAfter = requireNotNull(storage.sessionInputs.get("input"))
            if (appended) {
                assertEquals(inputBefore, inputAfter)
                assertEquals(SessionInputState.APPENDED, inputAfter.state)
                assertEquals(1, messagesBefore.count { it.id == inputAfter.messageId })
            } else {
                assertEquals(SessionInputState.NEEDS_ATTENTION, inputAfter.state)
                assertEquals(if (cancelling) "USER_STOP" else "PROCESS_INTERRUPTED", inputAfter.blockedReason)
                assertNull(inputAfter.messageId)
                assertNull(inputAfter.consumedTurnId)
            }
            assertEquals(if (requested) "model" else null, inputAfter.requestModelCallId)
            val modelsAfter = storage.modelCalls.listByTurn("turn")
            val auditAfter = storage.auditEvents.listByCorrelation("session")
            assertTrue(RecoveryCoordinatorApp(storage, SystemClock()).recover().interruptedTurns.isEmpty())
            assertEquals(inputAfter, storage.sessionInputs.get("input"))
            assertEquals(modelsAfter, storage.modelCalls.listByTurn("turn"))
            assertEquals(auditAfter, storage.auditEvents.listByCorrelation("session"))
        } finally {
            storage.close()
            context.deleteDatabase(database)
            content.deleteRecursively()
        }
    }

    private fun seedBoundary(
        storage: HelixStorage,
        appended: Boolean,
        requested: Boolean,
        cancelling: Boolean,
    ) {
        storage.sessions.create("session", "Recovery fixture", null, null, 1)
        var nextId = 0
        val coordinator =
            TurnCoordinator.start(
                storage,
                SystemClock(),
                { "generated-${nextId++}" },
                TurnStartSpec("session", "turn", "model", "fixture", "initial"),
            )
        val spec =
            SessionInputSpec(
                "input",
                "session",
                SessionInputDelivery.STEER,
                "turn",
                0,
                "supplement",
                emptyList(),
                InputConfiguration("provider", "model", "CHAT", "fingerprint"),
                2,
            )
        val accepted = storage.sessionInputs.accept(spec) as SessionInputAcceptResult.Accepted
        if (appended) {
            assertTrue(
                coordinator.appendSteeringBeforeRequest(
                    TurnSteeringDraft(accepted.record, spec.text, emptyList()),
                ),
            )
        }
        if (requested) {
            coordinator.beginModelStream()
            coordinator.recordInputRequestStarted(
                setOf(requireNotNull(storage.sessionInputs.get("input")?.messageId)),
            )
        }
        if (cancelling) {
            storage.withTransaction {
                val turn = storage.turns.resolve("turn")
                storage.turns.updateState(turn, TurnState.CANCELLING, turn.stepCount, null, null)
                storage.sessionInputs.parkSessionInputs("session", "USER_STOP", 3)
            }
        }
    }
}
