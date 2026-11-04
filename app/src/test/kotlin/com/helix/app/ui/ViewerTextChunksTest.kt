package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTextChunksTest {
    @Test fun chunksKeepCompleteContentAndUnicodePairs() {
        val text = "a".repeat(8191) + "😀" + "b".repeat(30_000)
        val chunks = viewerTextChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 8192 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
        assertTrue(viewerTextChunks("").isEmpty())
    }
}
