package com.helix.provider.catalog

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol

/**
 * The built-in provider template catalog (roadmap HXA-026, provider doc section 2.2/2.3).
 *
 * 15 templates: the P0 set (OpenAI, Anthropic, Generic OpenAI-compatible, Ollama, SGLang)
 * plus the P1 set (DeepSeek, DashScope/Qwen, OpenRouter, Moonshot/Kimi, Zhipu/GLM,
 * MiniMax, xAI, Groq, vLLM, LM Studio).
 *
 * Template content is ONLY protocol / official endpoint form / template-level headers —
 * never a model name and never a key (doc 2.2: “模板”只预填协议、官方 endpoint 形式和
 * 必要 header，不硬编码会过期的模型 ID). Official endpoints were verified against each
 * vendor's documentation before listing (completion record HXA-026); the endpoint path is
 * the API ROOT in the HXA-025 [com.helix.provider.api.WireModelProvider] sense
 * (the protocol resource path `/responses`, `/chat/completions`, `/messages`, `/models`
 * is appended by the adapter).
 *
 * “OpenAI-compatible” is a FAMILY of explicit templates (DeepSeek, DashScope, …), each
 * pinned to [ProviderProtocol.OPENAI_CHAT_COMPLETIONS] — never one fuzzy switch
 * (doc 2.1 protocol island discipline). A failed request never switches protocol.
 */
public object ProviderTemplateCatalog {
    // region P0 (doc 2.3 首批)

    /** OpenAI official API. Responses is preferred; the Chat Completions adapter stays for older services. */
    public val openAi: ProviderTemplate =
        ProviderTemplate(
            id = "openai",
            displayName = "OpenAI",
            priority = TemplatePriority.P0,
            protocol = ProviderProtocol.OPENAI_RESPONSES,
            defaultEndpoint = NormalizedEndpoint.parse("https://api.openai.com/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes =
                listOf(
                    "Responses API is preferred; the Chat Completions adapter remains available for older services.",
                ),
        )

    /** Anthropic official API (Messages). */
    public val anthropic: ProviderTemplate =
        ProviderTemplate(
            id = "anthropic",
            displayName = "Anthropic",
            priority = TemplatePriority.P0,
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            defaultEndpoint = NormalizedEndpoint.parse("https://api.anthropic.com/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /**
     * Generic OpenAI-compatible service: the user configures base URL, API key and model
     * ID (doc 2.2/2.3). No default endpoint.
     */
    public val genericOpenAi: ProviderTemplate =
        ProviderTemplate(
            id = "generic-openai",
            displayName = "Generic OpenAI-compatible",
            priority = TemplatePriority.P0,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = null,
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes =
                listOf(
                    "Enter the service base URL, API key and model ID. Claimed OpenAI compatibility is verified " +
                        "by the capability probe, not trusted from the name.",
                ),
        )

    /**
     * Ollama (doc 2.3: default `http://127.0.0.1:11434/v1`). LAN cleartext HTTP must be
     * enabled per explicit host and shows the risk (doc 2.5) — that gate is app-side
     * (HXA-027/028), recorded here as guidance.
     */
    public val ollama: ProviderTemplate =
        ProviderTemplate(
            id = "ollama",
            displayName = "Ollama",
            priority = TemplatePriority.P0,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("http://127.0.0.1:11434/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = false,
            notes =
                listOf(
                    "Default is loopback. LAN cleartext HTTP requires an explicit per-host authorization and a " +
                        "risk display (provider doc 2.5).",
                ),
        )

    /**
     * SGLang (doc 2.3: default `<server>/v1` — the host is user-specific, so no default
     * endpoint). Tool-call parsers are server-configured per model: run the capability
     * probe before relying on tool calls (doc 2.3/2.5).
     */
    public val sglang: ProviderTemplate =
        ProviderTemplate(
            id = "sglang",
            displayName = "SGLang",
            priority = TemplatePriority.P0,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = null,
            defaultHeaders = emptyMap(),
            credentialRequired = false,
            notes =
                listOf(
                    "Enter your SGLang server host and port (path /v1). Run the capability probe before using " +
                        "tool calls — the server-side tool-call parser must be configured for the model.",
                ),
        )

    // endregion

    // region P1 (doc 2.3 跟进)

    /** DeepSeek OpenAI-compatible API (verified: base_url https://api.deepseek.com, no /v1). */
    public val deepSeek: ProviderTemplate =
        ProviderTemplate(
            id = "deepseek",
            displayName = "DeepSeek",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://api.deepseek.com"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /** Alibaba DashScope/Qwen OpenAI-compatible mode (verified: /compatible-mode/v1). */
    public val dashScopeQwen: ProviderTemplate =
        ProviderTemplate(
            id = "dashscope-qwen",
            displayName = "DashScope/Qwen",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /**
     * OpenRouter aggregator. The only template with template-level headers: OpenRouter
     * attribution. `X-Title` carries the app name; `HTTP-Referer` (the app origin) is
     * left to the user because Helix ships no public web origin.
     */
    public val openRouter: ProviderTemplate =
        ProviderTemplate(
            id = "openrouter",
            displayName = "OpenRouter",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://openrouter.ai/api/v1"),
            defaultHeaders = mapOf("X-Title" to "Helix"),
            credentialRequired = true,
            notes =
                listOf(
                    "Aggregator: behavior (including tool calls) depends on the routed upstream model — the " +
                        "capability probe applies.",
                ),
        )

    /** Moonshot/Kimi OpenAI-compatible API. */
    public val moonShotKimi: ProviderTemplate =
        ProviderTemplate(
            id = "moonshot-kimi",
            displayName = "Moonshot/Kimi",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://api.moonshot.cn/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /** Zhipu/GLM OpenAI-compatible API (verified: /api/paas/v4). */
    public val zhipuGlm: ProviderTemplate =
        ProviderTemplate(
            id = "zhipu-glm",
            displayName = "Zhipu/GLM",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://open.bigmodel.cn/api/paas/v4"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /** MiniMax OpenAI-compatible API (verified: https://api.minimaxi.com/v1). */
    public val minimax: ProviderTemplate =
        ProviderTemplate(
            id = "minimax",
            displayName = "MiniMax",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://api.minimaxi.com/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /** xAI OpenAI-compatible API. */
    public val xAi: ProviderTemplate =
        ProviderTemplate(
            id = "xai",
            displayName = "xAI",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://api.x.ai/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /** Groq OpenAI-compatible API. */
    public val groq: ProviderTemplate =
        ProviderTemplate(
            id = "groq",
            displayName = "Groq",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("https://api.groq.com/openai/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = true,
            notes = emptyList(),
        )

    /**
     * Self-hosted vLLM: the host is user-specific (default server port 8000, path /v1),
     * so no default endpoint.
     */
    public val vllm: ProviderTemplate =
        ProviderTemplate(
            id = "vllm",
            displayName = "vLLM",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = null,
            defaultHeaders = emptyMap(),
            credentialRequired = false,
            notes =
                listOf(
                    "Enter your vLLM server host and port (default 8000, path /v1). API key optional.",
                ),
        )

    /**
     * LM Studio local server (default port 1234, path /v1). Like Ollama, LAN usage needs
     * the explicit per-host cleartext authorization (doc 2.5).
     */
    public val lmStudio: ProviderTemplate =
        ProviderTemplate(
            id = "lm-studio",
            displayName = "LM Studio",
            priority = TemplatePriority.P1,
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            defaultEndpoint = NormalizedEndpoint.parse("http://127.0.0.1:1234/v1"),
            defaultHeaders = emptyMap(),
            credentialRequired = false,
            notes =
                listOf(
                    "LM Studio local server (default 127.0.0.1:1234, path /v1). For another LAN machine use that " +
                        "host's IP; cleartext HTTP needs the explicit per-host authorization (provider doc 2.5).",
                ),
        )

    // endregion

    /** All 15 templates, P0 first in doc order, then P1 in doc order. */
    public val all: List<ProviderTemplate>
        get() =
            listOf(
                openAi,
                anthropic,
                genericOpenAi,
                ollama,
                sglang,
                deepSeek,
                dashScopeQwen,
                openRouter,
                moonShotKimi,
                zhipuGlm,
                minimax,
                xAi,
                groq,
                vllm,
                lmStudio,
            )

    /** Templates by id; null for unknown ids (no guess, fail closed at the call site). */
    public fun byId(id: String): ProviderTemplate? = all.firstOrNull { it.id == id }
}
