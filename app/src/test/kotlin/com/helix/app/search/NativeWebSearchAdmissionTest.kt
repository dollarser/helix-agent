package com.helix.app.search

import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SecretAlias
import com.helix.core.model.ToolName
import com.helix.provider.api.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWebSearchAdmissionTest {
    private val search = ModelToolSchema(ToolName("web_search"), "Search", """{"type":"object"}""")
    private val fetch = ModelToolSchema(ToolName("web_fetch"), "Fetch", """{"type":"object"}""")
    private val request =
        TurnContextRequest(
            "owner-model",
            listOf(ModelMessage(ModelRole.USER, "research")),
            listOf(search, fetch),
            2000,
            ReasoningEffort.OFF,
        )

    private fun config(
        endpoint: String = "https://api.openai.com/v1",
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_RESPONSES,
    ) = ProviderConfig(
        "fixture",
        "Fixture",
        protocol,
        NormalizedEndpoint.parse(endpoint),
        "owner-model",
        emptyMap(),
        SecretAlias("fixture"),
        "{}",
    )

    @Test fun explicitOptInKeepsModelAndRemovesDuplicateLocalSearch() {
        val result = NativeWebSearchAdmission.apply(request, config(), true)
        assertTrue(result.nativeWebSearch)
        assertTrue(result.modelRequest().nativeWebSearch)
        assertEquals("owner-model", result.model)
        assertEquals(listOf(fetch), result.tools)
        assertFalse(NativeWebSearchAdmission.apply(request, config(), false).nativeWebSearch)
    }

    @Test fun sessionDisabledOrModeFilteredSearchIsNotReintroduced() {
        val filtered = request.copy(tools = listOf(fetch))
        assertFalse(NativeWebSearchAdmission.apply(filtered, config(), true).nativeWebSearch)
        assertEquals(filtered.tools, NativeWebSearchAdmission.apply(filtered, config(), true).tools)
    }

    @Test fun compatibleEndpointAndChatProtocolDoNotInheritCapability() {
        for (endpoint in listOf(
            "https://proxy.example/v1",
            "http://api.openai.com/v1",
            "https://api.openai.com:8443/v1",
            "https://api.openai.com/other",
        )) {
            val result = NativeWebSearchAdmission.apply(request, config(endpoint), true)
            assertFalse(endpoint, result.nativeWebSearch)
            assertEquals(request.tools, result.tools)
        }
        assertFalse(
            NativeWebSearchAdmission
                .apply(
                    request,
                    config(protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS),
                    true,
                ).nativeWebSearch,
        )
    }
}
