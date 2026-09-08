package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.provider.SubscriptionRecoveryStatus
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Local discovery must survive newer turns without waking a Runtime or leaking another session. */
class SubscriptionRecoveryDiscoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "subscription-discovery-${UUID.randomUUID()}"
    private val content = File(context.cacheDir, name)
    private val storage = HelixStorage.open(context, name, content)

    @After fun cleanup() {
        storage.close()
        context.deleteDatabase(name)
        content.deleteRecursively()
    }

    @Test fun olderInterruptedCallRemainsAfterNewTurn() {
        session("s")
        call("old", "s", interrupted = true)
        call("new", "s", interrupted = false)
        assertEquals(listOf("call-old"), discover("s").map { it.modelCallId })
    }

    @Test fun anotherSessionAndClosedConversationNeverReuseVisibleRows() {
        session("a")
        session("b")
        call("a", "a", interrupted = true)
        val rows = discover("a")
        assertEquals(1, rows.size)
        assertTrue(subscriptionRecoveriesFor(storage, "b", rows).isEmpty())
        assertTrue(subscriptionRecoveriesFor(storage, null, rows).isEmpty())
    }

    @Test fun missingBindingWrongAuditTypeAndCompletedCallAreExcluded() {
        session("s")
        call("missing", "s", interrupted = true, auditType = null)
        call("wrong", "s", interrupted = true, auditType = "model.completed")
        call("completed", "s", interrupted = true, modelState = "COMPLETED")
        assertTrue(discover("s").isEmpty())
    }

    @Test fun refreshPreservesOnlyTheMatchingVisibleStatus() {
        session("s")
        call("one", "s", interrupted = true)
        call("two", "s", interrupted = true)
        val previous = SubscriptionRecoveryUi("one", "call-one", true, SubscriptionRecoveryStatus.RUNNING)
        val rows = subscriptionRecoveriesFor(storage, "s", listOf(previous))
        assertEquals(previous, rows.single { it.modelCallId == "call-one" })
        assertEquals(SubscriptionRecoveryUi("two", "call-two"), rows.single { it.modelCallId == "call-two" })
    }

    private fun discover(sessionId: String) = subscriptionRecoveriesFor(storage, sessionId, emptyList())

    private fun session(id: String) {
        storage.sessions.create(id, "Recovery fixture", null, null, 1L)
    }

    private fun call(
        id: String,
        session: String,
        interrupted: Boolean,
        auditType: String? = "cli.job_prepared",
        modelState: String = "INTERRUPTED",
    ) {
        val turn = storage.turns.start(id, session, 2L)
        if (interrupted) storage.turns.updateState(turn, TurnState.INTERRUPTED, 0, 3L, null)
        storage.modelCalls.append("call-$id", id, "fixture", modelState)
        if (auditType != null) {
            storage.auditEvents.append("cli-job-call-$id", session, auditType, "platform", "{}", 2L)
        }
    }
}
