package com.helix.app.ui

import com.helix.app.chat.BackgroundTaskUi
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkProjectionTest {
    private fun task(
        id: String,
        session: String,
        state: TurnState = TurnState.COMPLETED,
    ) = BackgroundTaskUi(id, session, "Same title", state, null, false, false)

    @Test fun currentSessionUsesIdentityAndMissingIdentityNeverShowsAll() {
        val rows = listOf(task("a", "one"), task("b", "two"))
        assertEquals(listOf(rows[0]), sessionTasks(rows, "one"))
        assertEquals(listOf(rows[1]), sessionTasks(rows, "two"))
        assertTrue(sessionTasks(rows, null).isEmpty())
        assertTrue(sessionTasks(rows, "deleted").isEmpty())
    }

    @Test fun failureCancellationAndUnknownResultsAreNotCompletedOutputs() {
        assertTrue(isDeliverableResult(task("ok", "one")))
        listOf(TurnState.FAILED, TurnState.CANCELLED, TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW).forEach {
            assertFalse(isDeliverableResult(task("other", "one", it)))
        }
    }
}
