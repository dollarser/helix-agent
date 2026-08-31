package com.helix.core.model

/**
 * Provider wire protocol (provider doc section 2.1). Responses, Chat Completions and
 * Anthropic Messages are distinct adapters with different request, stream-event, tool-result
 * and state semantics — never a fuzzy "OpenAI format" switch, and never a silent protocol
 * fallback after a failed request (architecture doc section 6.2).
 */
enum class ProviderProtocol {
    OPENAI_RESPONSES,
    OPENAI_CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    ;

    companion object {
        /**
         * Parses a persisted protocol name. ADR-0001 decode contract: any failure is an
         * [IllegalArgumentException] — a stale or corrupted row must fail closed, not be
         * guessed.
         */
        fun parse(name: String): ProviderProtocol =
            entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException(
                    "unknown provider protocol: $name (expected one of ${entries.joinToString { it.name }})",
                )
    }
}
