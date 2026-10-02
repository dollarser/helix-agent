package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollIndicatorsTest {
    @Test fun emptyAndShortListsHaveNoArtificialOverflow() {
        assertEquals(0f to 300f, lazyScrollThumb(300f, 0, 0f, 0f, 20f))
        assertEquals(0f to 300f, lazyScrollThumb(300f, 3, 0f, 3f, 20f))
    }

    @Test fun middleAndEndPositionsRemainInsideViewport() {
        val middle = lazyScrollThumb(200f, 100, 45f, 55f, 20f)
        val end = lazyScrollThumb(200f, 100, 90f, 100f, 20f)
        assertEquals(90f, middle.first, 0.01f)
        assertEquals(200f, end.first + end.second, 0.01f)
    }

    @Test fun singleTallItemStillShowsProgress() {
        val first = lazyScrollThumb(200f, 1, 0f, 0.25f, 20f)
        val last = lazyScrollThumb(200f, 1, 0.75f, 1f, 20f)
        assertEquals(50f, first.second, 0.01f)
        assertEquals(0f, first.first, 0.01f)
        assertEquals(200f, last.first + last.second, 0.01f)
    }

    @Test fun tinyViewportAndResizeNeverDrawOutsideTrack() {
        val thumb = lazyScrollThumb(8f, 1000, 999f, 1000f, 20f)
        assertTrue(thumb.first >= 0f && thumb.first + thumb.second <= 8f)
        assertEquals(0f to 0f, formScrollThumb(0f, 100f, 10f, 20f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteGeometryIsRejected() {
        formScrollThumb(200f, 100f, Float.NaN, 20f)
    }
}
