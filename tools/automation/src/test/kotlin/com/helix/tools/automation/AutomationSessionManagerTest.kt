package com.helix.tools.automation

import com.helix.core.model.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AutomationSessionManagerTest {
    private val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
    private var nextId = 0
    private val manager = AutomationSessionManager(clock) { "session-${++nextId}" }
    private val allowed = setOf("com.example.fixture", "org.example.second")

    @Test
    fun startsOneFiveMinuteSessionBoundToTheRequestedAllowlistedPackages() {
        val result = manager.start(setOf("com.example.fixture"), allowed)

        assertEquals(AutomationSessionStartStatus.STARTED, result.status)
        val session = result.session!!
        assertEquals("session-1", session.id)
        assertEquals(clock.now(), session.startedAt)
        assertEquals(setOf("com.example.fixture"), session.scope.allowedPackages)
        assertTrue(session.scope.deniedPackages.isEmpty())
        assertEquals(30, session.scope.maxActions)
        assertEquals(clock.now().plus(Duration.ofMinutes(5)), session.scope.expiresAt)
        assertSame(session, manager.current())
        assertNull(manager.lastStopReason)
    }

    @Test
    fun emptyInvalidAndNonAllowlistedTargetsFailClosed() {
        assertEquals(
            AutomationSessionStartStatus.EMPTY_TARGETS,
            manager.start(emptySet(), allowed).status,
        )
        assertEquals(
            AutomationSessionStartStatus.INVALID_PACKAGE,
            manager.start(setOf("Bad.Package"), allowed).status,
        )
        assertEquals(
            AutomationSessionStartStatus.TARGET_NOT_ALLOWLISTED,
            manager.start(setOf("com.example.other"), allowed).status,
        )
        assertNull(manager.current())
    }

    @Test
    fun ttlCannotBeZeroNegativeOrLongerThanTheFiveMinuteHardMaximum() {
        for (ttl in listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(5).plusMillis(1))) {
            assertEquals(
                AutomationSessionStartStatus.INVALID_TTL,
                manager.start(setOf("com.example.fixture"), allowed, ttl).status,
            )
        }
        assertNull(manager.current())
    }

    @Test
    fun actionBudgetCannotBeZeroOrExceedTheThirtyActionHardMaximum() {
        for (maxActions in listOf(0, AutomationSessionManager.MAX_ACTIONS + 1)) {
            assertEquals(
                AutomationSessionStartStatus.INVALID_ACTION_BUDGET,
                manager
                    .start(
                        setOf("com.example.fixture"),
                        allowed,
                        maxActions = maxActions,
                    ).status,
            )
        }
        assertNull(manager.current())
    }

    @Test
    fun aSecondSessionCannotReplaceTheLiveSession() {
        val first = manager.start(setOf("com.example.fixture"), allowed).session
        val second = manager.start(setOf("org.example.second"), allowed)

        assertEquals(AutomationSessionStartStatus.SESSION_ALREADY_ACTIVE, second.status)
        assertNull(second.session)
        assertSame(first, manager.current())
    }

    @Test
    fun exactDeadlineExpiresAndClearsTheSession() {
        val session = manager.start(setOf("com.example.fixture"), allowed).session!!
        clock.instant = session.scope.expiresAt

        assertNull(manager.current())
        assertEquals(AutomationStopReason.EXPIRED, manager.lastStopReason)
    }

    @Test
    fun clockRollbackFailsClosed() {
        val session = manager.start(setOf("com.example.fixture"), allowed).session!!
        clock.instant = session.startedAt.minusMillis(1)

        assertNull(manager.current())
        assertEquals(AutomationStopReason.CLOCK_ROLLBACK, manager.lastStopReason)
    }

    @Test
    fun allowlistReductionImmediatelyStopsAnAffectedSession() {
        manager.start(setOf("com.example.fixture", "org.example.second"), allowed)

        assertTrue(manager.reconcileAllowlist(setOf("com.example.fixture")))
        assertNull(manager.current())
        assertEquals(AutomationStopReason.ALLOWLIST_CHANGED, manager.lastStopReason)
    }

    @Test
    fun unrelatedAllowlistChangeDoesNotStopTheSession() {
        val session = manager.start(setOf("com.example.fixture"), allowed).session

        assertFalse(manager.reconcileAllowlist(setOf("com.example.fixture", "net.example.third")))
        assertSame(session, manager.current())
    }

    @Test
    fun explicitStopIsIdempotentAndRecordsOnlyTheRealTransition() {
        manager.start(setOf("com.example.fixture"), allowed)

        assertTrue(manager.stop(AutomationStopReason.USER_STOP))
        assertFalse(manager.stop(AutomationStopReason.SERVICE_DISCONNECTED))
        assertEquals(AutomationStopReason.USER_STOP, manager.lastStopReason)
    }

    @Test
    fun targetChangePausesUntilExplicitUserConfirmationAndStopClearsPause() {
        manager.start(setOf("com.example.fixture"), allowed)

        assertTrue(manager.pause(AutomationPauseReason.TARGET_CHANGED))
        assertTrue(manager.isPaused())
        assertEquals(AutomationPauseReason.TARGET_CHANGED, manager.pauseReason)
        assertTrue(manager.resumeAfterUserConfirmation())
        assertFalse(manager.isPaused())

        manager.pause(AutomationPauseReason.TARGET_CHANGED)
        manager.stop(AutomationStopReason.USER_STOP)
        assertNull(manager.pauseReason)
    }

    @Test
    fun everyTenAttemptsRequiresANewConfirmationThatCannotBeBanked() {
        manager.start(setOf("com.example.fixture"), allowed)

        repeat(9) {
            assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
            assertEquals(AutomationActionCompletion.CONTINUE, manager.completeAction())
        }
        assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
        assertEquals(AutomationActionCompletion.CHECKPOINT_REQUIRED, manager.completeAction())
        assertEquals(AutomationPauseReason.CHECKPOINT, manager.pauseReason)
        assertEquals(AutomationActionAdmission.SESSION_PAUSED, manager.admitAction())

        assertTrue(manager.resumeAfterUserConfirmation())
        assertFalse(manager.resumeAfterUserConfirmation())
        repeat(9) {
            assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
            assertEquals(AutomationActionCompletion.CONTINUE, manager.completeAction())
        }
        assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
        assertEquals(AutomationActionCompletion.CHECKPOINT_REQUIRED, manager.completeAction())
        assertEquals(AutomationPauseReason.CHECKPOINT, manager.pauseReason)
    }

    @Test
    fun configuredBudgetStopsTheSessionImmediatelyAfterItsFinalAttempt() {
        manager.start(setOf("com.example.fixture"), allowed, maxActions = 3)

        repeat(2) {
            assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
            assertEquals(AutomationActionCompletion.CONTINUE, manager.completeAction())
        }
        assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
        assertEquals(AutomationActionCompletion.BUDGET_EXHAUSTED, manager.completeAction())
        assertNull(manager.current())
        assertEquals(AutomationStopReason.ACTION_BUDGET_EXHAUSTED, manager.lastStopReason)
        assertEquals(AutomationActionAdmission.NO_ACTIVE_SESSION, manager.admitAction())
    }
}

private class MutableClock(
    var instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}
