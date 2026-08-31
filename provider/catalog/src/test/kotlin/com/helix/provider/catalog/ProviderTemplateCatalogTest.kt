package com.helix.provider.catalog

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-026 acceptance: the catalog is exactly the doc 2.3 set (P0 5 + P1 10), each entry is
 * ONLY protocol / official endpoint form / template-level headers (no model names, no
 * keys), and the endpoint paths are API roots in the HXA-025 WireModelProvider sense.
 */
class ProviderTemplateCatalogTest {
    private val catalog = ProviderTemplateCatalog.all

    @Test
    fun catalogHasFifteenTemplatesP0ThenP1() {
        assertEquals(15, catalog.size)
        assertEquals(5, catalog.count { it.priority == TemplatePriority.P0 })
        assertEquals(10, catalog.count { it.priority == TemplatePriority.P1 })
        // doc order: the P0 block comes first, P1 after it
        val firstP1Index = catalog.indexOfFirst { it.priority == TemplatePriority.P1 }
        assertTrue(catalog.take(firstP1Index).all { it.priority == TemplatePriority.P0 })
        assertTrue(catalog.drop(firstP1Index).all { it.priority == TemplatePriority.P1 })
    }

    @Test
    fun p0SetIsExactlyTheDocFirstBatch() {
        assertEquals(
            setOf("openai", "anthropic", "generic-openai", "ollama", "sglang"),
            catalog.filter { it.priority == TemplatePriority.P0 }.map { it.id }.toSet(),
        )
    }

    @Test
    fun p1SetIsExactlyTheDocFollowUpBatch() {
        assertEquals(
            setOf(
                "deepseek",
                "dashscope-qwen",
                "openrouter",
                "moonshot-kimi",
                "zhipu-glm",
                "minimax",
                "xai",
                "groq",
                "vllm",
                "lm-studio",
            ),
            catalog.filter { it.priority == TemplatePriority.P1 }.map { it.id }.toSet(),
        )
    }

    @Test
    fun idsAreUniqueStableSlugs() {
        val ids = catalog.map { it.id }
        assertEquals(15, ids.toSet().size)
        val slugPattern = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
        assertTrue(ids.all { it.matches(slugPattern) })
    }

    @Test
    fun displayNamesAreUniqueAndNonBlank() {
        val names = catalog.map { it.displayName }
        assertEquals(15, names.toSet().size)
        assertTrue(names.all { it.isNotBlank() })
    }

    @Test
    fun protocolsMatchDocAssignments() {
        val byId = catalog.associateBy { it.id }
        assertEquals(ProviderProtocol.OPENAI_RESPONSES, byId.getValue("openai").protocol)
        assertEquals(ProviderProtocol.ANTHROPIC_MESSAGES, byId.getValue("anthropic").protocol)
        catalog
            .filter { it.id != "openai" && it.id != "anthropic" }
            .forEach { template ->
                assertEquals(
                    "template ${template.id} must pin one explicit protocol",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    template.protocol,
                )
            }
    }

    /**
     * The official endpoint FORMS (verified against vendor documentation, completion
     * record HXA-026). Null = user must supply the host.
     */
    @Test
    fun officialEndpointForms() {
        val expected: Map<String, NormalizedEndpoint?> =
            catalog.associate { template ->
                val endpoint =
                    template.defaultEndpoint?.let {
                        NormalizedEndpoint.parse(it.full)
                    }
                template.id to endpoint
            }
        assertEquals(15, expected.size)
        checkEndpoint(expected, "openai", "https", "api.openai.com", 443, "/v1")
        checkEndpoint(expected, "anthropic", "https", "api.anthropic.com", 443, "/v1")
        assertNull(expected.getValue("generic-openai"))
        checkEndpoint(expected, "ollama", "http", "127.0.0.1", 11434, "/v1")
        assertNull(expected.getValue("sglang"))
        checkEndpoint(expected, "deepseek", "https", "api.deepseek.com", 443, "")
        checkEndpoint(expected, "dashscope-qwen", "https", "dashscope.aliyuncs.com", 443, "/compatible-mode/v1")
        checkEndpoint(expected, "openrouter", "https", "openrouter.ai", 443, "/api/v1")
        checkEndpoint(expected, "moonshot-kimi", "https", "api.moonshot.cn", 443, "/v1")
        checkEndpoint(expected, "zhipu-glm", "https", "open.bigmodel.cn", 443, "/api/paas/v4")
        checkEndpoint(expected, "minimax", "https", "api.minimaxi.com", 443, "/v1")
        checkEndpoint(expected, "xai", "https", "api.x.ai", 443, "/v1")
        checkEndpoint(expected, "groq", "https", "api.groq.com", 443, "/openai/v1")
        assertNull(expected.getValue("vllm"))
        checkEndpoint(expected, "lm-studio", "http", "127.0.0.1", 1234, "/v1")
    }

    private fun checkEndpoint(
        actual: Map<String, NormalizedEndpoint?>,
        id: String,
        scheme: String,
        host: String,
        port: Int,
        path: String,
    ) {
        val endpoint = actual.getValue(id)
        assertTrue("template $id must have a default endpoint", endpoint != null)
        assertEquals("$id scheme", scheme, endpoint!!.scheme)
        assertEquals("$id host", host, endpoint.host)
        assertEquals("$id port", port, endpoint.port)
        assertEquals("$id path (API root)", path, endpoint.path)
    }

    /**
     * Endpoint paths are API roots: the protocol resource path is appended by the
     * adapter, so a template path must never already contain a resource segment.
     */
    @Test
    fun endpointPathsAreApiRootsNotResourcePaths() {
        catalog
            .filter { it.defaultEndpoint != null }
            .forEach { template ->
                val path = template.defaultEndpoint!!.path
                assertTrue(
                    "template ${template.id} path must be empty or start with /",
                    path.isEmpty() || path.startsWith("/"),
                )
                assertFalse(
                    "template ${template.id} path must not end with /",
                    path.endsWith("/"),
                )
                listOf("responses", "chat/completions", "messages", "models").forEach { resource ->
                    assertFalse(
                        "template ${template.id} path must not embed resource '$resource'",
                        path.contains(resource),
                    )
                }
            }
    }

    @Test
    fun endpointsRoundTripThroughNormalization() {
        catalog
            .filter { it.defaultEndpoint != null }
            .forEach { template ->
                val endpoint = template.defaultEndpoint!!
                assertEquals(
                    "template ${template.id} endpoint must be canonical",
                    endpoint,
                    NormalizedEndpoint.parse(endpoint.full),
                )
            }
    }

    @Test
    fun credentialPolicyMatchesVendorApiForms() {
        val optionalKey =
            catalog.filter { !it.credentialRequired }.map { it.id }.toSet()
        assertEquals(setOf("ollama", "sglang", "vllm", "lm-studio"), optionalKey)
    }

    @Test
    fun onlyUserHostedTemplatesLackDefaultEndpoints() {
        val noDefault = catalog.filter { it.defaultEndpoint == null }.map { it.id }.toSet()
        assertEquals(setOf("generic-openai", "sglang", "vllm"), noDefault)
    }

    @Test
    fun onlyOpenRouterCarriesTemplateLevelHeaders() {
        catalog.forEach { template ->
            if (template.id == "openrouter") {
                assertEquals(mapOf("X-Title" to "Helix"), template.defaultHeaders)
            } else {
                assertEquals(
                    "template ${template.id} must carry no template headers",
                    emptyMap<String, String>(),
                    template.defaultHeaders,
                )
            }
        }
    }

    /**
     * Doc 2.2/2.3 discipline: templates pre-fill protocol/endpoint/headers only — never a
     * model name and never a key. Structural (the type has no model/key field) plus a
     * content scan over every string the template exposes.
     */
    @Test
    fun templatesContainNoModelNamesAndNoKeys() {
        catalog.forEach { template ->
            val strings =
                buildList {
                    add(template.id)
                    add(template.displayName)
                    addAll(template.notes)
                    addAll(template.defaultHeaders.values)
                    template.defaultEndpoint?.let { add(it.full) }
                }
            strings.forEach { value ->
                assertFalse(
                    "template ${template.id} must not embed a key-like value",
                    value.contains("sk-") || value.contains("Bearer "),
                )
            }
            // model IDs carry a vendor product prefix; none may appear in the user-facing
            // content (the id/displayName legitimately reference vendor names, so they are
            // excluded from the model-name scan)
            val modelScan =
                strings - template.id - template.displayName
            listOf("gpt-", "o1-", "o3-", "claude-", "deepseek-v", "qwen-", "glm-", "kimi-", "moonshot-", "llama-")
                .forEach { prefix ->
                    modelScan.forEach { value ->
                        assertFalse(
                            "template ${template.id} must not embed a model name ($prefix…)",
                            value.contains(prefix),
                        )
                    }
                }
        }
    }

    @Test
    fun residenceClassesFollowTheEndpointNotTheName() {
        val byId = catalog.associateBy { it.id }
        val publicCloud =
            listOf(
                "openai",
                "anthropic",
                "deepseek",
                "dashscope-qwen",
                "openrouter",
                "moonshot-kimi",
                "zhipu-glm",
                "minimax",
                "xai",
                "groq",
            )
        publicCloud.forEach { id ->
            assertEquals(
                "template $id residence",
                ProviderResidence.PUBLIC_CLOUD,
                byId.getValue(id).defaultEndpoint!!.residence(),
            )
        }
        assertEquals(
            ProviderResidence.ON_DEVICE_LOOPBACK,
            byId.getValue("ollama").defaultEndpoint!!.residence(),
        )
        assertEquals(
            ProviderResidence.ON_DEVICE_LOOPBACK,
            byId.getValue("lm-studio").defaultEndpoint!!.residence(),
        )
    }

    @Test
    fun docMandatedGuidanceIsPresent() {
        val byId = catalog.associateBy { it.id }
        assertTrue(
            "ollama note must cover the LAN cleartext gate",
            byId.getValue("ollama").notes.any { it.contains("cleartext", ignoreCase = true) },
        )
        assertTrue(
            "sglang note must mandate the capability probe before tool calls",
            byId.getValue("sglang").notes.any { it.contains("capability probe", ignoreCase = true) },
        )
        assertTrue(
            "generic template note must require base URL + model ID",
            byId.getValue("generic-openai").notes.any {
                it.contains("base URL", ignoreCase = true) && it.contains("model ID", ignoreCase = true)
            },
        )
    }

    @Test
    fun byIdResolvesEveryTemplateAndRejectsUnknownIds() {
        catalog.forEach { template ->
            assertEquals(template, ProviderTemplateCatalog.byId(template.id))
        }
        assertNull(ProviderTemplateCatalog.byId("openai-"))
        assertNull(ProviderTemplateCatalog.byId("OPENAI"))
        assertNull(ProviderTemplateCatalog.byId(""))
        assertNull(ProviderTemplateCatalog.byId("no-such-provider"))
    }
}
