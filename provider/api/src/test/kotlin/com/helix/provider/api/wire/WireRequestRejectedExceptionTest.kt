package com.helix.provider.api.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireRequestRejectedExceptionTest {
    @Test fun mediaRejectionDoesNotClaimAllImagesAreUnsupported() {
        val reason = WireRequestRejectedException.Reason.UNSUPPORTED_MEDIA
        val error = WireRequestRejectedException(reason)
        assertEquals(reason, error.reason)
        assertTrue(error.message.orEmpty().contains("media type or reference"))
        assertTrue(error.message.orEmpty().contains("supported image attachment"))
        assertFalse(error.message.orEmpty().contains("text and custom function tools only"))
    }
}
