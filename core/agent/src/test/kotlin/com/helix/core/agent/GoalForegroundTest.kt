package com.helix.core.agent

import com.helix.core.agent.GoalForegroundAction.OPEN
import com.helix.core.agent.GoalForegroundAction.PAUSE
import com.helix.core.agent.GoalForegroundAction.RESUME
import com.helix.core.model.GoalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 (research doc sections 13/15): [GoalForeground] — the honest bounded-foreground card for a
 * goal. A running goal offers [Pause]; a goal Android parked is "Paused by Android" with [Resume];
 * a user pause just says "Paused". The projection decides what to show and offer, never whether a
 * run may start — only an explicit resume does that.
 */
class GoalForegroundTest {
    @Test
    fun aRunningGoalShowsWorkingWithOpenAndPause() {
        val c = GoalForeground.card(GoalState.RUNNING, null)
        assertEquals("Working", c.statusLine)
        assertEquals(listOf(OPEN, PAUSE), c.actions)
        assertNull(c.progressLine)
    }

    @Test
    fun aRunningGoalWithProgressShowsTheStepLine() {
        val c = GoalForeground.card(GoalState.RUNNING, null, step = 3, totalSteps = 6)
        assertEquals("Step 3/6", c.progressLine)
    }

    @Test
    fun aGoalPausedByAndroidSaysSoAndOffersResume() {
        val c = GoalForeground.card(GoalState.PAUSED, GoalPauseReason.SYSTEM)
        assertEquals("Paused by Android", c.statusLine)
        assertEquals(listOf(OPEN, RESUME), c.actions)
    }

    @Test
    fun aGoalPausedByTheUserJustSaysPaused() {
        val c = GoalForeground.card(GoalState.PAUSED, GoalPauseReason.USER)
        assertEquals("Paused", c.statusLine)
        assertEquals(listOf(OPEN, RESUME), c.actions)
    }

    @Test
    fun aPausedGoalWithUnknownReasonSaysPausedButStillOffersResume() {
        val c = GoalForeground.card(GoalState.PAUSED, null)
        assertEquals("Paused", c.statusLine)
        assertTrue(RESUME in c.actions)
    }

    @Test
    fun aPausedGoalStillShowsItsStepProgress() {
        val c = GoalForeground.card(GoalState.PAUSED, GoalPauseReason.SYSTEM, step = 2, totalSteps = 5)
        assertEquals("Paused by Android", c.statusLine)
        assertEquals("Step 2/5", c.progressLine)
    }

    @Test
    fun anInputRequiredGoalShowsNeedsInputWithOpenOnly() {
        val c = GoalForeground.card(GoalState.INPUT_REQUIRED, null)
        assertEquals("Needs input", c.statusLine)
        assertEquals(listOf(OPEN), c.actions)
    }

    @Test
    fun readyAndDraftShowTheirStateWithOpenOnly() {
        assertEquals("Ready", GoalForeground.card(GoalState.READY, null).statusLine)
        assertEquals("Draft", GoalForeground.card(GoalState.DRAFT, null).statusLine)
        assertEquals(listOf(OPEN), GoalForeground.card(GoalState.READY, null).actions)
    }

    @Test
    fun aBlockedGoalShowsBlockedWithOpenOnly() {
        val c = GoalForeground.card(GoalState.BLOCKED, null)
        assertEquals("Blocked", c.statusLine)
        assertEquals(listOf(OPEN), c.actions)
    }

    @Test
    fun terminalGoalsShowTheirOutcomeWithOpenOnly() {
        assertEquals("Completed", GoalForeground.card(GoalState.COMPLETED, null).statusLine)
        assertEquals("Failed", GoalForeground.card(GoalState.FAILED, null).statusLine)
        assertEquals("Cancelled", GoalForeground.card(GoalState.CANCELLED, null).statusLine)
        assertEquals(listOf(OPEN), GoalForeground.card(GoalState.COMPLETED, null).actions)
    }

    @Test
    fun theProgressLineNeedsBothStepAndAPositiveTotal() {
        assertNull(GoalForeground.card(GoalState.RUNNING, null, step = 3, totalSteps = null).progressLine)
        assertNull(GoalForeground.card(GoalState.RUNNING, null, step = null, totalSteps = 6).progressLine)
        assertNull(GoalForeground.card(GoalState.RUNNING, null, step = 0, totalSteps = 0).progressLine)
        assertEquals("Step 0/4", GoalForeground.card(GoalState.RUNNING, null, step = 0, totalSteps = 4).progressLine)
    }

    @Test
    fun pauseReasonIsIgnoredOutsidePaused() {
        // A running goal is "Working" regardless of any pause reason passed in.
        assertEquals("Working", GoalForeground.card(GoalState.RUNNING, GoalPauseReason.SYSTEM).statusLine)
    }
}
