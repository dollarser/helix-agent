package com.helix.feature.conversation.application

import com.helix.core.model.CancelSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SettingsExecutionAuthorityTest {
    private class MutableCancellation : CancelSignal {
        var cancelled = false

        override fun isCancelled() = cancelled
    }

    @Test fun originalIdentityAndLiveCancellationSurviveQueuedAdmission() {
        val cancel = MutableCancellation()
        val deadline = Instant.parse("2026-11-07T12:00:00Z")
        val authority = SettingsExecutionAuthority("session", "turn", "call", "settings.apply", deadline, cancel)
        assertEquals("session", authority.sessionId)
        assertEquals("turn", authority.turnId)
        assertEquals("call", authority.toolCallId)
        assertEquals("settings.apply", authority.toolName)
        assertSame(cancel, authority.cancel)
        assertFalse(authority.isStopped(deadline.minusSeconds(1)))
        cancel.cancelled = true
        assertTrue(authority.isStopped(deadline.minusSeconds(1)))
    }

    @Test fun deadlineIsExclusiveAndCannotBeRefreshedByQueueing() {
        val deadline = Instant.parse("2026-11-07T12:00:00Z")
        val authority =
            SettingsExecutionAuthority("session", "turn", "call", "settings.apply", deadline, MutableCancellation())
        assertFalse(authority.isStopped(deadline.minusNanos(1)))
        assertTrue(authority.isStopped(deadline))
        assertTrue(authority.isStopped(deadline.plusNanos(1)))
    }
}
