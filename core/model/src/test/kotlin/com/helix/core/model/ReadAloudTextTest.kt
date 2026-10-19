package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTextTest {
    @Test fun visibleLinkLabelNeverSpeaksOrFetchesUrl() {
        val chunks = ReadAloudText.chunks("# **Hello** [来源](https://example.com/private)")
        assertEquals(listOf("Hello 来源"), chunks)
        assertFalse(chunks.joinToString().contains("https"))
    }

    @Test fun surrogatePairsAndLongTextRemainBounded() {
        val text = "合成文本😀".repeat(200)
        val chunks = ReadAloudText.chunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 64 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun decimalAndEmptyOrOversizedInputHaveHonestBoundaries() {
        assertEquals(listOf("预算为3.14元。"), ReadAloudText.chunks("预算为3.14元。"))
        assertTrue(runCatching { ReadAloudText.chunks(" ") }.isFailure)
        assertTrue(runCatching { ReadAloudText.chunks("x".repeat(20_001)) }.isFailure)
    }
}
