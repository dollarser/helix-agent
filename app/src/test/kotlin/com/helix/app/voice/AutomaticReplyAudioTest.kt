package com.helix.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticReplyAudioTest {
    private fun frame(
        turn: String = "turn",
        phase: VisibleReplyFrame.Phase = VisibleReplyFrame.Phase.STREAMING,
    ) = VisibleReplyFrame("session", turn, "Visible answer.", phase)

    @Test fun startsOnlyOptedInVisibleStreamingText() {
        val starts = AutomaticReplyAudio()
        assertFalse(starts.start(null, true))
        assertFalse(starts.start(frame(), false))
        assertFalse(starts.start(frame().copy(text = ""), true))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.COMPLETED), true))
        assertTrue(starts.start(frame(), true))
    }

    @Test fun tokenUpdatesWaitsAndSessionSwitchesDoNotReplayAnAnswer() {
        val starts = AutomaticReplyAudio()
        assertTrue(starts.start(frame(), true))
        assertFalse(starts.start(frame().copy(text = "Visible answer. More text."), true))
        assertFalse(starts.start(frame(phase = VisibleReplyFrame.Phase.WAITING), true))
        assertFalse(starts.start(frame(), true))
        assertTrue(starts.start(frame("another"), true))
        assertFalse(starts.start(frame(), true))
    }

    @Test fun laterModelStepAfterToolsMaySpeakWithoutReplayingEarlierStep() {
        val starts = AutomaticReplyAudio()
        val initial = frame().copy(modelStep = 1)
        assertTrue(starts.start(initial, true))
        assertFalse(starts.start(initial.copy(phase = VisibleReplyFrame.Phase.WAITING), true))
        assertTrue(starts.start(initial.copy(modelStep = 2, text = "Final answer."), true))
        assertFalse(starts.start(initial, true))
    }
}
