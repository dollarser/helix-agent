package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewHeaderGeometryTest {
    @Test fun smallPreviewKeepsCompactChromeAndLargerWindowsScaleContinuously() {
        assertEquals(20f, previewHeaderHeight(96f), 0.001f)
        assertEquals(20f, previewHeaderHeight(112f), 0.001f)
        assertEquals(22.4f, previewHeaderHeight(160f), 0.001f)
        assertEquals(28f, previewHeaderHeight(260f), 0.001f)
    }
}
