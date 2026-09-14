package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolPurposeTest {
    @Test fun summaryUsesTargetRatherThanFileBodyOrCommand() {
        assertEquals("a.txt", ToolPurpose.target("""{"path":"a.txt","content":"private text"}"""))
        assertEquals("b.txt", ToolPurpose.target("""{"source":"a.txt","destination":"b.txt"}"""))
        assertNull(ToolPurpose.target("""{"command":"long command","content":"body"}"""))
        assertNull(ToolPurpose.target("invalid"))
    }
}
