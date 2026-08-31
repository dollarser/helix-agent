package com.helix.app.provider

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderResidence
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.catalog.ProviderTemplateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderComposerTest {
    private val openai = ProviderTemplateCatalog.byId("openai")!!
    private val ollama = ProviderTemplateCatalog.byId("ollama")!!
    private val generic = ProviderTemplateCatalog.byId("generic-openai")!!

    @Test
    fun templatePrefillsEndpointProtocolAndCredentialPolicy() {
        val outcome =
            ProviderComposer.compose(
                openai,
                "OpenAI",
                "https://api.openai.com/v1",
                "gpt-5",
                emptyMap(),
            )
        val draft = (outcome as ComposeOutcome.Ok).draft
        assertEquals("openai", draft.templateId)
        assertEquals(NormalizedEndpoint.parse("https://api.openai.com/v1"), draft.endpoint)
        assertEquals(ProviderResidence.PUBLIC_CLOUD, draft.residence)
        assertEquals("https://api.openai.com:443", draft.origin) // canonical origin always carries the port
        assertNull(draft.cleartext)
        assertFalse(draft.isCleartext)
        assertTrue(draft.credentialRequired)
    }

    @Test
    fun residenceFollowsTheEndpointNotTheTemplateName() {
        // The ollama template's DEFAULT endpoint is loopback, but a user may
        // point it at the LAN or a public host — residence must follow the
        // actual endpoint (doc 10 section 2.5; FR-LLM-009).
        assertEquals(
            ProviderResidence.ON_DEVICE_LOOPBACK,
            (
                ProviderComposer.compose(ollama, "本地", "http://127.0.0.1:11434/v1", "qwen2.5", emptyMap())
                    as ComposeOutcome.Ok
            ).draft.residence,
        )
        assertEquals(
            ProviderResidence.USER_AUTHORIZED_LAN,
            (
                ProviderComposer.compose(ollama, "本地", "http://192.168.1.20:11434/v1", "qwen2.5", emptyMap())
                    as ComposeOutcome.Ok
            ).draft.residence,
        )
        assertEquals(
            ProviderResidence.USER_AUTHORIZED_LAN,
            (
                ProviderComposer.compose(ollama, "模拟器", "http://10.0.2.2:11434/v1", "qwen2.5", emptyMap())
                    as ComposeOutcome.Ok
            ).draft.residence,
        )
        assertEquals(
            ProviderResidence.PUBLIC_CLOUD,
            (
                ProviderComposer.compose(
                    generic,
                    "公网",
                    "https://llm.example.com/v1",
                    "m",
                    emptyMap(),
                ) as ComposeOutcome.Ok
            ).draft.residence,
        )
    }

    @Test
    fun cleartextEndpointYieldsTheExactHostPortAuthorization() {
        val draft =
            (
                ProviderComposer.compose(ollama, "本地", "http://192.168.1.20:11434/v1", "qwen2.5", emptyMap())
                    as ComposeOutcome.Ok
            ).draft
        assertEquals(CleartextAuthorization("192.168.1.20", 11434), draft.cleartext)
        assertTrue(draft.isCleartext)
    }

    @Test
    fun credentialRequiredIsRefusedWithoutAKeyByTheUiContract() {
        // The composer records the policy; the UI (and ProviderService) refuse
        // to save a credential-required provider without a key (FR-LLM-001).
        val openaiDraft =
            ProviderComposer.compose(openai, "x", "https://api.openai.com/v1", "m", emptyMap())
                as ComposeOutcome.Ok
        val ollamaDraft =
            ProviderComposer.compose(ollama, "x", "http://127.0.0.1:11434/v1", "m", emptyMap()) as ComposeOutcome.Ok
        assertTrue(openaiDraft.draft.credentialRequired)
        assertFalse(ollamaDraft.draft.credentialRequired)
    }

    @Test
    fun blankDisplayNameAndModelAreRejectedWithUserVisibleReasons() {
        val name =
            ProviderComposer.compose(openai, "  ", "https://api.openai.com/v1", "m", emptyMap())
                as ComposeOutcome.Rejected
        assertTrue(name.reason.isNotBlank())
        val model =
            ProviderComposer.compose(openai, "x", "https://api.openai.com/v1", "bad\u0000model", emptyMap())
                as ComposeOutcome.Rejected
        assertTrue(model.reason.isNotBlank())
    }

    @Test
    fun invalidEndpointsAreRejectedFailClosed() {
        val raws =
            listOf(
                "ftp://api.openai.com/v1",
                "https://user:pass@api.openai.com/v1",
                "https://api.openai.com/v1?x=1",
                "not a url",
            )
        for (raw in raws) {
            val outcome = ProviderComposer.compose(openai, "x", raw, "m", emptyMap())
            assertTrue("expected rejection for $raw", outcome is ComposeOutcome.Rejected)
        }
    }

    @Test
    fun credentialLookingHeaderNamesAreRejected() {
        val outcome =
            ProviderComposer.compose(openai, "x", "https://api.openai.com/v1", "m", mapOf("X-Api-Key" to "abc"))
        assertTrue(outcome is ComposeOutcome.Rejected)
    }

    @Test
    fun templateAndCustomHeadersMergeCaseInsensitivelyAndPassTheAllowlist() {
        // OpenRouter carries one template header; a user may add another.
        val openrouter = ProviderTemplateCatalog.byId("openrouter")!!
        val draft =
            (
                ProviderComposer.compose(
                    openrouter,
                    "x",
                    "https://openrouter.ai/api/v1",
                    "m",
                    mapOf("x-title" to "Helix", "X-Custom" to "v"),
                ) as ComposeOutcome.Ok
            ).draft
        // Same value under different case is a merge, not a collision.
        assertTrue(draft.headersJson.contains("x-title"))
        assertTrue(draft.headersJson.contains("x-custom"))
    }

    @Test
    fun conflictingHeaderValuesAreRejected() {
        val openrouter = ProviderTemplateCatalog.byId("openrouter")!!
        val outcome =
            ProviderComposer.compose(openrouter, "x", "https://openrouter.ai/api/v1", "m", mapOf("x-title" to "Other"))
        assertTrue(outcome is ComposeOutcome.Rejected)
    }

    @Test
    fun headersJsonRoundTripsThroughTheAllowlist() {
        val draft =
            (
                ProviderComposer.compose(ollama, "x", "http://127.0.0.1:11434/v1", "m", mapOf("X-Ollama" to "1"))
                    as ComposeOutcome.Ok
            ).draft
        val parsed = ProviderHeaders.parse(draft.headersJson)
        assertTrue(parsed.containsKey("x-ollama"))
    }

    @Test
    fun unknownTemplateIdsResolveToNull() {
        assertNull(ProviderComposer.resolveTemplate("does-not-exist"))
    }
}
