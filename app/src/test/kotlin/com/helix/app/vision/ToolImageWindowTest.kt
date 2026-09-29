package com.helix.app.vision

import com.helix.core.model.VisionLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolImageWindowTest {
    @Test fun onlyLatestTwoImagesFromTheCurrentTurnAreSelected() {
        assertEquals(
            setOf(3, 5),
            ToolImageWindow.select("current", listOf("old", "current", null, "current", "old", "current")),
        )
    }

    @Test fun successorTurnDoesNotAutomaticallyReloadOldToolImages() {
        assertEquals(emptySet<Int>(), ToolImageWindow.select("new", listOf("old", "old")))
        assertEquals(emptySet<Int>(), ToolImageWindow.select(null, listOf(null, null)))
    }

    @Test fun userImagesShareTheTotalWireBudget() {
        val max = VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong()
        assertEquals(0L, ToolImageWindow.remaining(listOf(max, max, max, max)))
        assertEquals(4L, ToolImageWindow.base64Bytes(1))
        assertEquals(4L, ToolImageWindow.base64Bytes(3))
        assertEquals(8L, ToolImageWindow.base64Bytes(4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedImageIsNotCountedAsASmallReference() {
        ToolImageWindow.base64Bytes(Long.MAX_VALUE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooManyReferenceBytesAreRejected() {
        ToolImageWindow.remaining(List(5) { VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong() })
    }
}
