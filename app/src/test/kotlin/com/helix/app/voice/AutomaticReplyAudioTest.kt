package com.helix.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticReplyAudioTest {
    private fun frame(
        turn: String = "turn",
        phase: VisibleReplyFrame.Phase = VisibleReplyFrame.Phase.STREAMING,
    ) = VisibleReplyFrame("session", turn, "Visible answer.", phase)

    @Test fun onlyAConfirmedFinalAfterOptedInActivityStarts() {
        val starts = AutomaticReplyAudio()
        assertFalse(starts.start(null, true))
        assertFalse(starts.start(frame(), false))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
        assertFalse(starts.start(frame(), true))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.FINALIZING), true))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED).copy(text = "  "), true))
        assertTrue(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
    }

    @Test fun toolStepsAndIntermediateTextNeverSpeakAndFinalIsOncePerTurn() {
        val starts = AutomaticReplyAudio()
        assertFalse(starts.start(frame().copy(modelStep = 1), true))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.WAITING), true))
        assertFalse(starts.start(frame().copy(modelStep = 2), true))
        val final = frame(phase = VisibleReplyFrame.Phase.COMPLETED).copy(modelStep = 3)
        assertTrue(starts.start(final, true))
        assertFalse(starts.start(final, true))
        assertFalse(starts.start(final.copy(modelStep = 4), true))
        assertFalse(starts.start(frame(), true))
        assertFalse(starts.start(final, true))
    }

    @Test fun disabledCancelledOrNavigationCannotStartALateFinal() {
        for (boundary in listOf<VisibleReplyFrame?>(null, frame(phase = VisibleReplyFrame.Phase.CANCELLED))) {
            val starts = AutomaticReplyAudio()
            assertFalse(starts.start(frame(), true))
            assertFalse(starts.start(boundary, true))
            assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
        }
        val starts = AutomaticReplyAudio()
        starts.start(frame(), true)
        assertFalse(starts.start(frame(), false))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
        starts.start(frame(), true)
        assertFalse(starts.start(frame("history", VisibleReplyFrame.Phase.COMPLETED), true))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
    }

    @Test fun leavingAnActiveReplyDoesNotBackfillItsCompletion() {
        val starts = AutomaticReplyAudio()
        starts.start(frame(), true)
        starts.leave()
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
        starts.start(frame("new"), true)
        assertTrue(starts.start(frame("new", VisibleReplyFrame.Phase.COMPLETED), true))
    }

    @Test fun historicalFinalLoadingCannotArmPlayback() {
        val starts = AutomaticReplyAudio()
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.FINALIZING), true))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
    }

    @Test fun anotherSessionWithTheSameTurnIdIsNotTheArmedTurn() {
        val starts = AutomaticReplyAudio()
        starts.start(frame(), true)
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED).copy(sessionId = "other"), true))
        starts.start(frame(), true)
        assertTrue(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
        starts.start(frame("next"), true)
        assertTrue(starts.start(frame("next", VisibleReplyFrame.Phase.COMPLETED), true))
    }
}
