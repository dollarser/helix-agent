package com.helix.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashFrameEvidenceTest {
    @Test fun retainsLocationsAndCausesButNotMessagesOrSourcePaths() {
        val inner = IllegalArgumentException("PRIVATE_MESSAGE")
        inner.stackTrace = arrayOf(StackTraceElement("com.helix.Fixture", "execute", "/PRIVATE/path.kt", 42))
        val outer = IllegalStateException("Bearer PRIVATE_TOKEN", inner)
        outer.stackTrace = arrayOf(StackTraceElement("PRIVATE secret", "PRIVATE secret", "secret", 1))
        val frames = CrashFrameEvidence.frames(outer)
        assertTrue(frames.contains("com.helix.Fixture.execute:42"))
        assertTrue(frames.contains("cause:java.lang.IllegalArgumentException"))
        assertFalse(frames.joinToString().contains("PRIVATE"))
    }

    @Test fun boundsDeepCauseAndFrameVolume() {
        var error: Throwable = IllegalStateException()
        repeat(10) { error = IllegalStateException(error) }
        assertEquals(4, CrashFrameEvidence.frames(error).count { it.startsWith("cause:") })
        assertTrue(CrashFrameEvidence.frames(error).size <= 36)
    }
}
