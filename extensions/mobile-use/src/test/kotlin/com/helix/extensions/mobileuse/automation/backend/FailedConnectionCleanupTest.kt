package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class FailedConnectionCleanupTest {
    @Test fun successfulReleaseStillPropagatesTheOriginalBootstrapFailure() {
        val failure = IllegalStateException("connect failed")
        var released = 0
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                releaseFailedConnection(failure) { released++ }
            }
        assertSame(failure, thrown)
        assertEquals(1, released)
        assertEquals(0, thrown.suppressed.size)
    }

    @Test fun disconnectWhileConnectingIsSuppressedWithoutReplacingTheOriginalCause() {
        val failure = SecurityException("UiAutomation connection already registered")
        val cleanup = IllegalStateException("Cannot disconnect while connecting")
        val thrown =
            assertThrows(SecurityException::class.java) {
                releaseFailedConnection(failure) { throw cleanup }
            }
        assertSame(failure, thrown)
        assertSame(cleanup, thrown.suppressed.single())
    }

    @Test fun cancellationRemainsCancellationAndCleanupCannotSuppressItOrSuppressItself() {
        val cancelled = CancellationException("cancelled")
        val thrown =
            assertThrows(CancellationException::class.java) {
                releaseFailedConnection(cancelled) { throw cancelled }
            }
        assertSame(cancelled, thrown)
        assertEquals(0, thrown.suppressed.size)
    }
}
