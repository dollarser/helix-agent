package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewFrameSizeTest {
    @Test fun windowPixelDemandSelectsBoundedTiersWithHysteresis() {
        assertEquals(240, previewFrameWidth(336f, 240))
        assertEquals(480, previewFrameWidth(525f, 240))
        assertEquals(720, previewFrameWidth(780f, 480))
        assertEquals(720, previewFrameWidth(700f, 720))
        assertEquals(480, previewFrameWidth(650f, 720))
        assertEquals(240, previewFrameWidth(360f, 240))
        assertEquals(480, previewFrameWidth(360f, 480))
        assertEquals(240, previewFrameWidth(300f, 720))
    }
}
