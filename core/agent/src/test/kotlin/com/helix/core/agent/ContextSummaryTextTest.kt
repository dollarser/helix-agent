package com.helix.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryTextTest {
    @Test fun capturedTextToolCallIsNotContinuityNotes() {
        assertTrue(ContextSummaryText.isToolCallOnly(CAPTURED))
    }

    @Test fun consecutiveEnvelopesWithOnlyWhitespaceAreRejected() {
        assertTrue(ContextSummaryText.isToolCallOnly(" \n$CAPTURED\t$CAPTURED \n"))
        assertTrue(ContextSummaryText.isToolCallOnly("<tool_call></tool_call>"))
    }

    @Test fun ordinaryNotesAndLiteralMarkersWithProseRemainAdmissible() {
        listOf(
            "目标：保留三条记录。尚未验证第三条。",
            "Goal: finish the task. Verified: two records. Remaining: one record.",
            "Observed malformed output: $CAPTURED",
            "$CAPTURED\nThe prior output was invalid; retrieve the original record.",
            "$CAPTURED\nPreserve this quoted example.\n$CAPTURED",
            "`<tool_call>example</tool_call>`",
            "<tool_call>unfinished",
            "",
        ).forEach { assertFalse(it, ContextSummaryText.isToolCallOnly(it)) }
    }

    companion object {
        // Exact assistant-text body of the archived summary response, not a structured tool call.
        const val CAPTURED =
            "\n\n<tool_call>\n<function=helix>\n<parameter=name>\nui.snapshot\n" +
                "</parameter>\n<parameter=args>\n{}\n</parameter>\n</function>\n</tool_call>"
    }
}
