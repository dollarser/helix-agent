package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun verifyDisconnectedGoal(
    storage: HelixStorage,
    goal: String,
    session: String,
) {
    val stored = storage.goals.resolve(goal)
    val run = storage.goalRuns.listByGoal(goal).single()
    val turn = storage.turns.listBySession(session).single()
    assertEquals("FAILED", stored.state)
    assertEquals("FAILED", run.outcome)
    assertEquals("FAILED", turn.state)
    assertEquals(1, stored.modelCalls)
    assertEquals(0L, stored.currentWakeMillis)
    assertTrue(run.endedAt != null)
    assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
    assertEquals(1, storage.modelCalls.listByTurn(turn.id).size)
    assertTrue(storage.toolCalls.listByTurn(turn.id).isEmpty())
}
