package com.helix.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationFailureLogTest {
    @Test fun excludesMessagesPathsAndSuppressedContentButRetainsFailureLocation() {
        val cause = IllegalStateException("token=private-token plugin configuration body")
        cause.stackTrace = arrayOf(StackTraceElement("com.helix.Store", "read", "/private/document.md", 42))
        val failure = RuntimeException("private conversation", cause)
        failure.addSuppressed(IllegalArgumentException("private attachment"))
        val text = OperationFailureLog.format(OperationFailureLog.Stage.MOBILE_SCOPE_LOAD, failure)
        assertTrue(text.contains("MOBILE_SCOPE_LOAD"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("com.helix.Store.read:42"))
        listOf("private-token", "configuration body", "private conversation", "private attachment", "/private/")
            .forEach { assertFalse(text.contains(it)) }
    }

    @Test fun boundsCauseDepthAndStackFrames() {
        val failure = IllegalStateException("secret")
        failure.stackTrace = Array(100) { StackTraceElement("com.helix.Store", "frame$it", "File", it) }
        val text = OperationFailureLog.format(OperationFailureLog.Stage.TERMINAL_OPEN, failure)
        assertTrue(text.contains("frame11:11"))
        assertFalse(text.contains("frame12:12"))
    }
}
