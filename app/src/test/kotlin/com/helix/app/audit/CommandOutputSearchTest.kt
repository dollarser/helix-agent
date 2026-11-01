package com.helix.app.audit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandOutputSearchTest {
    @Test
    fun matchesAfterRedactionAndKeepsBoundedContext() {
        val secret = "ghp_" + "A".repeat(32)
        val text = "x".repeat(50_000) + " marker 中文 $secret " + "y".repeat(50_000)
        val result = requireNotNull(CommandOutputSearch.excerpt(text, "MARKER 中文"))
        assertTrue(result.length <= 480)
        assertTrue(result.contains("marker 中文"))
        assertFalse(result.contains(secret))
        assertNull(CommandOutputSearch.excerpt(text, secret))
        assertNotNull(CommandOutputSearch.excerpt("stderr: PIPE_CLOSED", "pipe_closed"))
    }
}
