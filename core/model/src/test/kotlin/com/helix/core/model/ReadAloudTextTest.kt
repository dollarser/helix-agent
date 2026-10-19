package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTextTest {
    @Test fun shortOpeningClauseDoesNotWaitForWholeReply() {
        val text = "今天是2026年10月19日，预算137元。银行行长正在检查重庆的项目。"
        val chunks = ReadAloudText.chunks(text)
        assertEquals("今天是2026年10月19日，", chunks.first())
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun englishWordsAndNumericGroupsRemainIntactAtOpeningBoundary() {
        val text = "Review extraordinary configuration, budget 1,234,567.89 at 10:30:45 today."
        val spoken = ReadAloudText.chunks(text).joinToString(" ")
        listOf("extraordinary", "configuration", "1,234,567.89", "10:30:45").forEach {
            assertTrue(spoken.contains(it))
        }
        assertTrue(ReadAloudText.chunks("x".repeat(200)).all { it.length <= 64 })
    }

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
