package com.helix.app.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalReminderPayloadTest {
    @Test
    fun textContainsObjectiveAndInvitation() {
        val text = GoalReminderPayload.text("Investigate the login flow")
        assertEquals("Goal checkpoint: Investigate the login flow - open Helix to continue.", text)
    }

    @Test
    fun textIsBoundedForLongObjectives() {
        val longObjective = "x".repeat(10_000)
        val text = GoalReminderPayload.text(longObjective)
        // The objective is truncated to 128 characters, so the full notification text is
        // bounded: prefix + 128 + suffix.
        val bound = "Goal checkpoint: ".length + 128 + " - open Helix to continue.".length
        assertTrue("text of ${text.length} chars exceeds the $bound bound", text.length <= bound)
    }

    @Test
    fun textHandlesBlankObjective() {
        val text = GoalReminderPayload.text("   ")
        assertEquals("Goal checkpoint - open Helix to continue.", text)
    }

    @Test
    fun textNeverContainsATimeOrDuration() {
        // The UI must not present a checkpoint as an exact timer (architecture doc 5.1).
        val text = GoalReminderPayload.text("Goal at noon")
        assertFalse(text.contains("ms"))
        assertFalse(text.contains("秒"))
        assertFalse(text.contains("分钟"))
        assertFalse(text.matches(Regex(".*\\d{2}:\\d{2}.*")))
    }

    @Test
    fun uniqueWorkNameIsStablePerGoal() {
        assertEquals("goal-reminder-goal-1", GoalReminderPayload.uniqueWorkName("goal-1"))
        assertEquals(GoalReminderPayload.uniqueWorkName("goal-1"), GoalReminderPayload.uniqueWorkName("goal-1"))
    }
}
