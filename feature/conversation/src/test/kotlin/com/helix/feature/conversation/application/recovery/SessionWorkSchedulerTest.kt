package com.helix.feature.conversation.application.recovery

import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionWorkSchedulerTest {
    @Test fun stopDuringRecoveryPreventsQueueDeliveryAfterTheSuspensionBoundary() {
        val fixture = Fixture()
        try {
            fixture.scheduler { fixture.store.state = fixture.store.state.copy(stopped = true) }.requestDrain("session")
            assertTrue(fixture.consumed.isEmpty())
            assertEquals(0, fixture.handoff.resumes)
        } finally {
            fixture.close()
        }
    }

    @Test fun queuedInputWinsAndTerminalHandoffIsReservedSynchronously() {
        val fixture = Fixture()
        try {
            val scheduler = fixture.scheduler()
            assertTrue(scheduler.prepareAfterTerminal("session", "turn", TurnState.COMPLETED))
            assertEquals("turn", fixture.handoff.owner)
            assertTrue(fixture.consumed.isEmpty())
            scheduler.requestDrain("session", "turn")
            assertEquals(listOf("input"), fixture.consumed)
            assertEquals(0, fixture.handoff.resumes)
            assertEquals(null, fixture.handoff.owner)
        } finally {
            fixture.close()
        }
    }

    @Test fun stoppedTerminalCannotReserveAnotherAutomaticOwner() {
        val fixture = Fixture()
        try {
            fixture.store.facts = TerminalWorkFacts(true, true, false)
            assertFalse(fixture.scheduler().prepareAfterTerminal("session", "turn", TurnState.CANCELLED))
            assertEquals(null, fixture.handoff.owner)
            assertTrue(fixture.handoff.disarmed)
        } finally {
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        val store = Store()
        val handoff = Handoff()
        val consumed = mutableListOf<String>()
        private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

        fun scheduler(recovery: suspend (String) -> Unit = {}) =
            SessionWorkScheduler(
                store,
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(1)
                },
                scope,
                Mutex(),
                Any(),
                { false },
                handoff,
                { consumed += it.inputId },
                { error("unexpected continuation") },
                {},
                { throw it },
                recovery,
            )

        override fun close() {
            scope.cancel()
        }
    }

    private class Store : SessionWorkStore {
        var state = SessionWorkSnapshot(false, QueuedSessionWork("input", "session", 2, true, null), true)
        var facts = TerminalWorkFacts(true, false, true)

        override fun snapshot(sessionId: String) = state

        override fun terminalFacts(
            sessionId: String,
            turnId: String,
        ) = facts

        override fun parkAfterFailure(
            sessionId: String,
            at: Long,
        ) = error("unexpected failure")
    }

    private class Handoff : GoalContinuationHandoff {
        var owner: String? = null
        var resumes = 0
        var disarmed = false

        override fun disarm(sessionId: String) {
            disarmed = true
            owner = null
        }

        override fun reserveUserHandoff(
            sessionId: String,
            turnId: String,
        ) {
            owner = turnId
        }

        override fun reserveEligibleHandoff(
            sessionId: String,
            ownerTurnId: String,
        ) {
            owner = ownerTurnId
        }

        override fun handoffOwner(sessionId: String): String? = owner

        override fun resumeEligible(sessionId: String): SubmitTurnCommand? {
            resumes++
            return null
        }

        override fun finishHandoff(
            sessionId: String,
            previousTurnId: String,
        ) {
            if (owner ==
                previousTurnId
            ) {
                owner = null
            }
        }
    }
}
