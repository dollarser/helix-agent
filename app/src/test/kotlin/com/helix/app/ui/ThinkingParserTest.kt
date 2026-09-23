package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingParserTest {
    @Test
    fun parseReturnsNullThinkingWhenNoThinkTagsPresent() {
        val parsed = ThinkingParser.parse("Hello world!")
        assertNull(parsed.thinking)
        assertEquals("Hello world!", parsed.text)
        assertFalse(parsed.isStreaming)
    }

    @Test
    fun parseExtractsThinkingAndTextWhenThinkTagsComplete() {
        val raw = "<think>\nAnalyzing the code structure...\nFound solution.\n</think>\nHere is the answer."
        val parsed = ThinkingParser.parse(raw)
        assertEquals("Analyzing the code structure...\nFound solution.", parsed.thinking)
        assertEquals("Here is the answer.", parsed.text)
        assertFalse(parsed.isStreaming)
    }

    @Test
    fun parseHandlesUnclosedThinkTagAsStreaming() {
        val raw = "<think>\nStill reasoning through step 2..."
        val parsed = ThinkingParser.parse(raw)
        assertEquals("Still reasoning through step 2...", parsed.thinking)
        assertEquals("", parsed.text)
        assertTrue(parsed.isStreaming)
    }

    @Test
    fun parseHandlesEmptyThinkingTags() {
        val raw = "<think></think>Direct response"
        val parsed = ThinkingParser.parse(raw)
        assertNull(parsed.thinking)
        assertEquals("Direct response", parsed.text)
        assertFalse(parsed.isStreaming)
    }
}
