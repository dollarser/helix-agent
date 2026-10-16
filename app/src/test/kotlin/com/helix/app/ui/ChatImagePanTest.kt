package com.helix.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImagePanTest {
    @Test fun landscapeImageCannotDisappearIntoVerticalLetterboxing() {
        assertEquals(Offset.Zero, previewPanBounds(1200, 600, IntSize(400, 800), 1f))
        assertEquals(Offset(200f, 0f), previewPanBounds(1200, 600, IntSize(400, 800), 2f))
        assertEquals(Offset(800f, 100f), previewPanBounds(1200, 600, IntSize(400, 800), 5f))
    }

    @Test fun portraitAndUnmeasuredViewportsKeepOnlyUsefulPanRange() {
        assertEquals(Offset(0f, 200f), previewPanBounds(600, 1200, IntSize(800, 400), 2f))
        assertEquals(Offset.Zero, previewPanBounds(600, 1200, IntSize.Zero, 3f))
        assertEquals(Offset.Zero, previewPanBounds(0, 0, IntSize(800, 400), 3f))
    }
}
