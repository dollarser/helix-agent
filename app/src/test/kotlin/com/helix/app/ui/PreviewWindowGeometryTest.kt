package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewWindowGeometryTest {
    @Test fun portraitPreviewCanUseTheWholeSafeAreaWithoutReservedChrome() {
        val maximum = previewMaximumWidth(344f, 704f, 0.5f)
        assertEquals(344f, maximum, 0.001f)
        assertTrue(maximum > (704f - 260f) * 0.5f)
    }

    @Test fun phoneTabletLandscapeAndKeyboardLayoutsPreserveAspectRatio() {
        assertEquals(344f, previewMaximumWidth(344f, 704f, 0.5f), 0.001f)
        assertEquals(500f, previewMaximumWidth(800f, 1000f, 0.5f), 0.001f)
        assertEquals(704f, previewMaximumWidth(704f, 344f, 2.1f), 0.001f)
        assertEquals(70f, previewMaximumWidth(344f, 140f, 0.5f), 0.001f)
    }

    @Test fun doubleTapRepeatedlyTogglesCompactAndTheCurrentMaximum() {
        for (maximum in listOf(175f, 344f, 700f)) {
            var width = 112f
            repeat(4) {
                width = previewToggleWidth(width, maximum)
                assertEquals(maximum, width, 0.001f)
                width = previewToggleWidth(width, maximum)
                assertEquals(112f, width, 0.001f)
            }
            assertEquals(112f, previewToggleWidth(150f, maximum), 0.001f)
        }
    }

    @Test fun landscapeMinimumKeepsCloseReachableAndShortFramesHaveNoOverlappingHandle() {
        for (ratio in listOf(0.5f, 2.1f, 3f)) {
            val minimum = previewMinimumWidth(ratio)
            val width = boundedPreviewWidth(1f, 700f, minimum)
            assertTrue(width / ratio >= 48f)
            assertEquals(maxOf(112f, minimum), previewToggleWidth(700f, 700f, minimum), 0.001f)
        }
        assertTrue(!previewShowsResizeHandle(112f, 2.1f))
        assertTrue(!previewShowsResizeHandle(151.1f, 2.1f))
        assertTrue(previewShowsResizeHandle(152f, 2.1f))
        assertEquals(50f, boundedPreviewWidth(1f, 50f, previewMinimumWidth(3f)), 0.001f)
    }

    @Test fun tinyViewportsNeverToggleToAnUnreachableSize() {
        for (maximum in listOf(1f, 50f, 96f, 112f)) {
            assertEquals(maximum, previewToggleWidth(maximum, maximum), 0.001f)
        }
    }
}
