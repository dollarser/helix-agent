package com.helix.app.ui

import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolPurposeTest {
    @Test fun mobileActionsDescribeTheActualStepWithoutShowingInputPayloads() {
        assertEquals(R.string.tool_purpose_launch, ToolPurpose.actionRes("ui.launch"))
        assertEquals(R.string.tool_purpose_snapshot, ToolPurpose.actionRes("ui.snapshot"))
        assertEquals(R.string.tool_purpose_click, ToolPurpose.actionRes("ui.click_match"))
        assertEquals(R.string.tool_purpose_input, ToolPurpose.actionRes("ui.set_text"))
        assertEquals(R.string.tool_purpose_back, ToolPurpose.actionRes("ui.back"))
        assertEquals(R.string.tool_purpose_open_link, ToolPurpose.actionRes("android.open_uri"))
        assertEquals(R.string.tool_purpose_interact, ToolPurpose.actionRes("ui.future_action"))
        assertEquals(R.string.tool_purpose_execute, ToolPurpose.actionRes("custom.operation"))
        assertNull(ToolPurpose.target("""{"text":"private input","targetToken":"opaque-node-id"}"""))
    }

    @Test fun summaryUsesTargetRatherThanFileBodyOrCommand() {
        assertEquals("a.txt", ToolPurpose.target("""{"path":"a.txt","content":"private text"}"""))
        assertEquals("b.txt", ToolPurpose.target("""{"source":"a.txt","destination":"b.txt"}"""))
        assertNull(ToolPurpose.target("""{"command":"long command","content":"body"}"""))
        assertNull(ToolPurpose.target("invalid"))
    }

    @Test fun fileTitlesShowOnlyTheFilenameAndLeaveOtherTargetsIntact() {
        assertEquals("报告.txt", ToolPurpose.target("""{"path":"scope:ws-123:docs/报告.txt"}"""))
        assertEquals("报告.txt", ToolPurpose.target("""{"path":"docs/报告.txt"}"""))
        assertEquals("报告.txt", ToolPurpose.target("""{"source":"a/报告.txt","destination":"b/报告.txt"}"""))
        assertEquals(".", ToolPurpose.target("""{"path":"scope:ws-123:."}"""))
        val long = "scope:ws-123:" + "directory/".repeat(20) + "报告.txt"
        assertEquals("报告.txt", ToolPurpose.target("""{"path":"$long"}"""))
        assertEquals(
            "https://example.com/docs/report",
            ToolPurpose.target("""{"url":"https://example.com/docs/report"}"""),
        )
        assertEquals("docs/report", ToolPurpose.target("""{"query":"docs/report"}"""))
        val title = requireNotNull(ToolPurpose.target("""{"path":"docs/${"long-name".repeat(30)}.txt"}"""))
        org.junit.Assert.assertTrue(title.length <= 120)
        org.junit.Assert.assertTrue(title.endsWith(".txt"))
        org.junit.Assert.assertFalse(title.contains("docs/"))
    }
}
