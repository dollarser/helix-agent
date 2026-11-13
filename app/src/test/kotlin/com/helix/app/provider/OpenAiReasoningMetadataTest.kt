package com.helix.app.provider

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderTransport
import com.helix.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiReasoningMetadataTest {
    @Test fun exactModelContractsHaveDifferentEffortsWithoutGenerating() {
        assertEquals(emptyList<ReasoningEffort>(), resolve("gpt-4.1")?.reasoningEfforts)
        assertEquals(listOf("MINIMAL", "LOW", "MEDIUM", "HIGH"), resolve("gpt-5")?.reasoningEfforts?.map { it.name })
        assertEquals(listOf("NONE", "LOW", "MEDIUM", "HIGH"), resolve("gpt-5.1")?.reasoningEfforts?.map { it.name })
        assertEquals(
            listOf("NONE", "LOW", "MEDIUM", "HIGH", "XHIGH"),
            resolve("gpt-5.2")?.reasoningEfforts?.map { it.name },
        )
        assertEquals(resolve("gpt-5.2"), resolve("gpt-5.2-2025-12-11"))
        assertNull(resolve("gpt-5.2-custom"))
        assertNull(resolve("gpt-unknown"))
    }

    @Test fun proxiesOtherPathsPortsAndProtocolsNeverInheritOfficialFacts() {
        listOf(
            "https://proxy.test/v1",
            "https://api.openai.com.evil.test/v1",
            "http://api.openai.com/v1",
            "https://api.openai.com:444/v1",
            "https://api.openai.com/custom",
        ).forEach { assertNull(resolve("gpt-5.2", it)) }
        assertEquals(resolve("gpt-5.2"), resolve("gpt-5.2", "https://api.openai.com/v1/"))
        assertNull(
            OpenAiReasoningMetadata.forModel(
                ProviderTransport.Network(
                    ProviderProtocol.ANTHROPIC_MESSAGES,
                    NormalizedEndpoint.parse("https://api.openai.com/v1"),
                ),
                "gpt-5.2",
            ),
        )
    }

    private fun resolve(
        model: String,
        endpoint: String = "https://api.openai.com/v1",
    ) = OpenAiReasoningMetadata.forModel(
        ProviderTransport.Network(ProviderProtocol.OPENAI_RESPONSES, NormalizedEndpoint.parse(endpoint)),
        model,
    )
}
