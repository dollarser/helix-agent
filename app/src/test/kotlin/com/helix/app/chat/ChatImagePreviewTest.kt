package com.helix.app.chat

import com.helix.core.model.VisionLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InputStream

class ChatImagePreviewTest {
    @Test fun emptyAndNonProgressingStreamsAreUnavailable() {
        assertNull(boundedImageBytes(byteArrayOf().inputStream()))
        val stalled =
            object : InputStream() {
                override fun read(): Int = 0

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = 0
            }
        assertNull(boundedImageBytes(stalled))
    }

    @Test fun boundedReadPreservesBytesAndRejectsOversizeDespiteMetadata() {
        val small = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(small, boundedImageBytes(small.inputStream()))
        assertNull(boundedImageBytes(ByteArray(VisionLimits.MAX_INPUT_BYTES.toInt() + 1).inputStream()))
    }
}
