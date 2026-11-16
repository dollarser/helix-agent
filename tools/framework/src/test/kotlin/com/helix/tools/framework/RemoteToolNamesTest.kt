package com.helix.tools.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteToolNamesTest {
    @Test fun mcpNamesArePortableStableAndSourceQualified() {
        val name = RemoteToolNames.mcp("docs", "admin.tools.list")
        assertEquals(name, RemoteToolNames.mcp("docs", "admin.tools.list"))
        assertTrue(name.value.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertTrue(name.value.startsWith("mcp_docs_admin_tools_"))
        assertNotEquals(name, RemoteToolNames.mcp("other", "admin.tools.list"))
        assertNotEquals(name, RemoteToolNames.mcp("docs", "admin_tools_list"))
        assertNotEquals(RemoteToolNames.mcp("a_b", "c"), RemoteToolNames.mcp("a", "b_c"))
    }

    @Test fun fullMcpNameLengthAndCaseArePreservedInIdentity() {
        val long = "a".repeat(128)
        assertTrue(RemoteToolNames.mcp("server".repeat(10), long).value.length <= 64)
        assertNotEquals(RemoteToolNames.mcp("srv", "Read"), RemoteToolNames.mcp("srv", "read"))
        for (name in listOf("", "a".repeat(129), "with space", "a/b")) {
            assertThrows(IllegalArgumentException::class.java) { RemoteToolNames.mcp("srv", name) }
        }
    }

    @Test fun a2aUsesExactSkillIdentityAndCannotCollideWithMcp() {
        val unicode = RemoteToolNames.a2a("agent", "总结/任务")
        assertTrue(unicode.value.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertNotEquals(unicode, RemoteToolNames.a2a("agent", "总结_任务"))
        assertNotEquals(RemoteToolNames.mcp("agent", "echo"), RemoteToolNames.a2a("agent", "echo"))
    }
}
