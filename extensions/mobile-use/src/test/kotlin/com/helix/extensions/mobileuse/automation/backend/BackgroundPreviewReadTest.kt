package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BackgroundPreviewReadTest {
    @Test fun stableFrameNeedsNoPauseOrSecondRead() {
        val bytes = byteArrayOf(1)
        var reads = 0
        assertSame(
            bytes,
            readBackgroundPreview({
                reads++
                BackgroundPreviewRead.Frame(bytes)
            }, { error("pause") }),
        )
        assertEquals(1, reads)
    }

    @Test fun transitionCanRecoverWithoutPublishingAnEmptyFrame() {
        val bytes = byteArrayOf(2)
        var reads = 0
        var pauses = 0
        val frame =
            readBackgroundPreview({
                if (++reads < 3) BackgroundPreviewRead.Transition else BackgroundPreviewRead.Frame(bytes)
            }, { pauses++ })
        assertSame(bytes, frame)
        assertEquals(3, reads)
        assertEquals(2, pauses)
    }

    @Test fun persistentMissingTargetHasBoundedReadsAndNoCachedFallback() {
        var reads = 0
        var pauses = 0
        assertNull(
            readBackgroundPreview({
                reads++
                BackgroundPreviewRead.Transition
            }, { pauses++ }),
        )
        assertEquals(3, reads)
        assertEquals(2, pauses)
    }

    @Test fun refusalNeverRetriesOrWaits() {
        var reads = 0
        assertNull(
            readBackgroundPreview({
                reads++
                BackgroundPreviewRead.Refused
            }, { error("pause") }),
        )
        assertEquals(1, reads)
    }

    @Test fun revocationDuringTransitionStopsBeforeAThirdRead() {
        var reads = 0
        var pauses = 0
        assertNull(
            readBackgroundPreview({
                if (++reads == 1) BackgroundPreviewRead.Transition else BackgroundPreviewRead.Refused
            }, { pauses++ }),
        )
        assertEquals(2, reads)
        assertEquals(1, pauses)
    }
}
