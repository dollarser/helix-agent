package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewResizeGeometryTest {
    @Test fun compactCornerDragStillFollowsTheFrameAspectRatio() {
        assertEquals(40f, previewWidthDelta(40f, 80f, 0.5f), 0.001f)
        assertEquals(-40f, previewWidthDelta(-40f, -80f, 0.5f), 0.001f)
        assertEquals(40f, previewWidthDelta(40f, 20f, 2f), 0.001f)
    }

    @Test fun sizesStayInsideAvailableSpaceIncludingSmallKeyboardViewport() {
        assertEquals(96f, boundedPreviewWidth(1f, 400f))
        assertEquals(400f, boundedPreviewWidth(1000f, 400f))
        assertEquals(50f, boundedPreviewWidth(112f, 50f))
        assertEquals(183f, boundedPreviewWidth(183f, 400f))
    }
}
