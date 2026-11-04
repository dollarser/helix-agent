package com.helix.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ViewerTextPageTest {
    @Test fun utf8PagesPreserveEveryScalarAcrossAllBoundaryAlignments() {
        for (padding in 0..15) {
            val text = "x".repeat(padding) + "中🙂a+é".repeat(100)
            val bytes = text.toByteArray()
            val pages =
                (bytes.indices step 16).map { offset ->
                    decodeViewerTextPage(bytes.copyOfRange(offset, minOf(bytes.size, offset + 19)), 16)
                }
            assertEquals(text, pages.joinToString(""))
        }
    }

    @Test fun countsUseLongArithmeticWithoutOverflowAndIncludeEmptyFiles() {
        assertEquals(1, viewerPageCount(0, 16))
        assertEquals(1, viewerPageCount(16, 16))
        assertEquals(2, viewerPageCount(17, 16))
        assertEquals(Long.MAX_VALUE, viewerPageCount(Long.MAX_VALUE, 1))
        assertThrows(IllegalArgumentException::class.java) { viewerPageCount(-1, 16) }
    }
}
