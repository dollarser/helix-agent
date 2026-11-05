package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackgroundPreviewEncodingTest {
    @Test fun smallWindowKeepsExistingQualityAndAvoidsUnneededAttempts() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val bytes =
            encodeBackgroundPreview(240) { width, quality ->
                calls += width to quality
                byteArrayOf(1)
            }
        assertEquals(listOf(240 to 75), calls)
        assertEquals(1, bytes?.size)
    }

    @Test fun oversizedFramesLowerQualityThenResolutionWithinFixedPayloadLimit() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val bytes =
            encodeBackgroundPreview(720) { width, quality ->
                calls += width to quality
                ByteArray(
                    if (width ==
                        480
                    ) {
                        BackgroundPreviewTransport.MAX_BYTES
                    } else {
                        BackgroundPreviewTransport.MAX_BYTES + 1
                    },
                )
            }
        assertEquals(listOf(720 to 82, 720 to 55, 480 to 82), calls)
        assertEquals(BackgroundPreviewTransport.MAX_BYTES, bytes?.size)
    }

    @Test fun emptyOrOversizedPayloadsCannotBeReportedAsValidFrames() {
        var calls = 0
        assertNull(
            encodeBackgroundPreview(720) { _, _ ->
                calls++
                byteArrayOf()
            },
        )
        assertEquals(6, calls)
        assertThrows(IllegalArgumentException::class.java) {
            encodeBackgroundPreview(10000) { _, _ -> error("Must not encode invalid dimensions") }
        }
    }
}
