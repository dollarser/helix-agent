package com.helix.core.agent

/** Detects text-only tool envelopes without executing or interpreting their payload. */
internal object ContextSummaryText {
    fun isToolCallOnly(text: String): Boolean {
        var remaining = text.trim()
        var matched = false
        while (remaining.startsWith(OPEN)) {
            val end = remaining.indexOf(CLOSE, OPEN.length)
            if (end < 0) return false
            remaining = remaining.substring(end + CLOSE.length).trimStart()
            matched = true
        }
        return matched && remaining.isEmpty()
    }

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"
}
