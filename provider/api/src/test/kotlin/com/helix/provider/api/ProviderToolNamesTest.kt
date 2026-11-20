package com.helix.provider.api

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderToolNamesTest {
    @Test fun registeredNamesStayUnchangedAcrossOrderAndCatalogExpansion() {
        val values = listOf("tools_search", "tools_read_result", "files_list", "read", "hx_literal")
        val initial = ProviderToolNames(request(values))
        val expanded = ProviderToolNames(request(listOf("files_stat") + values.reversed()))
        values.forEach { name ->
            assertEquals(name, initial.encode(name))
            assertEquals(name, expanded.encode(name))
            assertEquals(name, initial.decode(name))
        }
        assertNull(initial.decode("files_stat"))
        assertThrows(IllegalArgumentException::class.java) { initial.encode("missing") }
    }

    @Test fun legalBoundaryNamesAndLargeDeclarationsAreUnchanged() {
        val values = listOf("a", "b".repeat(64)) + (0 until 4096).map { "mcp_fixture_tool_$it" }
        val names = ProviderToolNames(request(values))
        assertEquals(values, values.map(names::encode))
        assertEquals(values, values.map(names::decode))
        assertThrows(IllegalArgumentException::class.java) { request(listOf("same", "same")) }
    }

    @Test fun historyIsRepresentableButDoesNotAuthorizeNewCalls() {
        val old = AssistantToolCall(ToolCallId("old"), ToolName("retired_tool"), "{}")
        val current =
            request(listOf("files_read")).copy(
                messages =
                    listOf(
                        ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(old)),
                        ModelMessage(ModelRole.TOOL, "ok", toolCallId = old.id, toolName = old.name),
                    ),
            )
        val names = ProviderToolNames(current)
        assertEquals("retired_tool", names.encode("retired_tool"))
        assertNull(names.decode("retired_tool"))
        assertEquals("files_read", names.decode("files_read"))
        assertNull(ProviderToolNames(request(listOf("browser_click"))).decode("files_read"))
        assertNull(ProviderToolNames(request(emptyList())).decode("read"))
    }

    @Test fun oldDottedHistoryIsRejectedRatherThanSilentlyRetargeted() {
        assertThrows(IllegalArgumentException::class.java) { ToolName("tools.search") }
    }

    private fun request(names: List<String>) =
        ModelRequest(
            model = "synthetic",
            messages = listOf(ModelMessage(ModelRole.USER, "test")),
            tools = names.map { ModelToolSchema(ToolName(it), "test", """{"type":"object"}""") },
        )
}
