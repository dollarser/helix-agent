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
    @Test
    fun verifiedTargetRestoresOnlyAnExistingLiveGrant() {
        manager.start(allowed, allowed)
        manager.pause(AutomationPauseReason.TARGET_CHANGED)
        assertFalse(manager.resumeOnVerifiedTarget("com.other.app"))
        assertTrue(manager.isPaused())
        assertTrue(manager.resumeOnVerifiedTarget(allowed.first()))
        assertFalse(manager.isPaused())
        manager.pause(AutomationPauseReason.TARGET_CHANGED)
        manager.stop(AutomationStopReason.USER_STOP)
        assertFalse(manager.resumeOnVerifiedTarget(allowed.first()))
        manager.start(allowed, allowed)
        manager.pause(AutomationPauseReason.TARGET_CHANGED)
        clock.instant = clock.now().plus(Duration.ofMinutes(6))
        assertFalse(manager.resumeOnVerifiedTarget(allowed.first()))
    }

    private val clock = MutableClock(Instant.parse("2026-09-05T00:00:00Z"))
    private var nextId = 0
    private val manager = AutomationSessionManager(clock) { "session-${++nextId}" }
    private val allowed = setOf("com.example.fixture", "org.example.second")

    @Test
    fun recoveryConfirmationIsBoundToOneLivePauseAndAllowedTarget() {
        manager.start(allowed, allowed)
        assertFalse(manager.requestResumeOnTarget(allowed.first()))
        manager.pause(AutomationPauseReason.TARGET_CHANGED)
        assertFalse(manager.requestResumeOnTarget("com.other.app"))
        assertTrue(manager.requestResumeOnTarget(allowed.first()))
        assertEquals(allowed.first(), manager.resumeTarget)
        assertTrue(manager.isPaused())
        manager.resumeAfterUserConfirmation()
        assertNull(manager.resumeTarget)
        manager.pause(AutomationPauseReason.CHECKPOINT)
        assertNull(manager.resumeTarget)
        manager.requestResumeOnTarget(allowed.first())
        manager.stop(AutomationStopReason.USER_STOP)
        assertNull(manager.resumeTarget)
        manager.start(allowed, allowed)
        assertNull(manager.resumeTarget)
    }

    @Test
    fun systemSettingsGrantDoesNotSurviveStopExpiryOrRecreation() {
        assertFalse(manager.start(allowed, allowed).session!!.allowSystemSettings)
        manager.stop(AutomationStopReason.USER_STOP)
        assertTrue(manager.start(allowed, allowed, allowSystemSettings = true).session!!.allowSystemSettings)
        manager.stop(AutomationStopReason.USER_STOP)
        assertFalse(manager.start(allowed, allowed).session!!.allowSystemSettings)
        manager.stop(AutomationStopReason.USER_STOP)
        manager.start(allowed, allowed, allowSystemSettings = true)
        clock.instant = clock.now().plus(Duration.ofMinutes(6))
        assertNull(manager.current())
        assertFalse(manager.start(allowed, allowed).session!!.allowSystemSettings)
        assertFalse(AutomationSessionManager(clock).start(allowed, allowed).session!!.allowSystemSettings)
    }

    @Test
    fun systemSettingsGrantCannotExpandThePackageAllowlist() {
        assertEquals(
            AutomationSessionStartStatus.TARGET_NOT_ALLOWLISTED,
            manager.start(setOf("com.android.settings"), allowed, allowSystemSettings = true).status,
        )
    }

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
    fun authorizedActionsContinueAcrossPeriodicBoundariesWithoutHumanConfirmation() {
        manager.start(setOf("com.example.fixture"), allowed)

        repeat(29) {
            assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
            assertEquals(AutomationActionCompletion.CONTINUE, manager.completeAction())
            assertNull(manager.pauseReason)
            assertFalse(manager.resumeAfterUserConfirmation())
        }
        assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
        assertEquals(AutomationActionCompletion.BUDGET_EXHAUSTED, manager.completeAction())
        assertNull(manager.current())
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
