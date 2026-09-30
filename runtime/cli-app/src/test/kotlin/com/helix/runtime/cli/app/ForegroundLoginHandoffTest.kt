package com.helix.runtime.cli.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundLoginHandoffTest {
    @Test fun browserCallbackWaitsForForegroundAndRunsOnce() {
        val handoff = ForegroundLoginHandoff<String>()
        handoff.resume()
        handoff.pause()
        assertNull(handoff.offer("code"))
        assertEquals("code", handoff.resume())
        assertNull(handoff.resume())
    }

    @Test fun cancellationDropsPendingCodeAndForegroundCallbackRunsImmediately() {
        val handoff = ForegroundLoginHandoff<String>()
        handoff.offer("cancelled")
        handoff.clear()
        assertNull(handoff.resume())
        assertEquals("fresh", handoff.offer("fresh"))
        assertNull(handoff.resume())
    }
}
