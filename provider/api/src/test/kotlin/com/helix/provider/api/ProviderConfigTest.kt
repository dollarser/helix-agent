package com.helix.provider.api

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.model.SecretAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderConfigTest {
    private fun headersJson() = ProviderHeaders.toStorageString(mapOf("x-org" to "acme"))

    private fun fromStorage(
        endpoint: String = "https://api.openai.com/v1",
        protocol: String = "OPENAI_RESPONSES",
        headersJson: String = headersJson(),
        secretAlias: String = "provider-openai-key",
    ) = ProviderConfig.fromStorage(
        id = "provider-openai",
        displayName = "OpenAI",
        protocol = protocol,
        endpoint = endpoint,
        model = "gpt-test-model",
        headersJson = headersJson,
        secretAlias = secretAlias,
        capabilitySnapshot = """{"streaming":true}""",
    )

    @Test
    fun fromStorageParsesAValidRow() {
        val config = fromStorage()
        assertEquals("provider-openai", config.id)
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, config.protocol)
        assertEquals(NormalizedEndpoint.parse("https://api.openai.com/v1"), config.endpoint)
        assertEquals(mapOf("x-org" to "acme"), config.headers)
        assertEquals(SecretAlias("provider-openai-key"), config.secretAlias)
        assertEquals(ProviderResidence.PUBLIC_CLOUD, config.residence())
    }

    @Test
    fun sameTemplateDifferentEndpointsKeepDifferentResidences() {
        // The config carries the endpoint-derived residence, never a manual label: the same
        // template pointed at loopback/LAN/public yields three different classes.
        assertEquals(
            ProviderResidence.ON_DEVICE_LOOPBACK,
            fromStorage(endpoint = "http://127.0.0.1:11434/v1").residence(),
        )
        assertEquals(
            ProviderResidence.USER_AUTHORIZED_LAN,
            fromStorage(endpoint = "http://192.168.1.50:11434/v1").residence(),
        )
        assertEquals(
            ProviderResidence.PUBLIC_CLOUD,
            fromStorage(endpoint = "https://ollama.example.com:11434/v1").residence(),
        )
    }

    @Test
    fun fromStorageRejectsCorruptedRows() {
        val bad =
            listOf(
                { fromStorage(protocol = "OPENAI") }, // unknown protocol
                { fromStorage(protocol = "openai_responses") }, // case-sensitive closed set
                { fromStorage(endpoint = "ftp://x.com") }, // non-http(s) scheme
                { fromStorage(endpoint = "http://user:pw@x.com") }, // userinfo
                { fromStorage(headersJson = """{"Authorization":"x"}""") }, // credential header
                { fromStorage(headersJson = "not json") }, // corrupted JSON
                { fromStorage(secretAlias = "../escape") }, // alias traversal
                { fromStorage(secretAlias = "") }, // blank alias
            )
        bad.forEach { build ->
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun constructorRejectsUnsafeFields() {
        val valid = fromStorage()
        listOf(
            { valid.copy(id = "") },
            { valid.copy(id = "x".repeat(65)) },
            { valid.copy(displayName = "   ") },
            { valid.copy(displayName = "x".repeat(129)) },
            { valid.copy(model = "") },
            { valid.copy(model = "x".repeat(257)) },
            { valid.copy(model = "a\u0001b") },
            { valid.copy(capabilitySnapshot = "") },
            { valid.copy(headers = mapOf("X-Org" to "acme")) }, // non-canonical (uppercase) map
        ).forEach { build ->
            assertThrows(IllegalArgumentException::class.java) { build() }
        }
    }

    @Test
    fun roundTripThroughStorageColumns() {
        val config = fromStorage()
        val restored =
            ProviderConfig.fromStorage(
                id = config.id,
                displayName = config.displayName,
                protocol = config.protocol.name,
                endpoint = config.endpoint.full,
                model = config.model,
                headersJson = ProviderHeaders.toStorageString(config.headers),
                secretAlias = config.secretAlias.value,
                capabilitySnapshot = config.capabilitySnapshot,
            )
        assertEquals(config, restored)
    }
}
