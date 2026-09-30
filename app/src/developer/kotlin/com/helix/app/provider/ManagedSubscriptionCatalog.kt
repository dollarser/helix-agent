package com.helix.app.provider

import com.helix.core.model.ProviderProtocol
import com.helix.runtime.cli.client.CliModelProvider

/** Presentation order is explicit, independent of Room order, locale, and enum ordinal. */
internal data class ManagedSubscriptionEntry(
    val platform: CliModelProvider,
    val label: String,
    val protocol: ProviderProtocol,
    val endpoint: String,
    val initialModel: String,
    val activity: String,
) {
    val id: String get() = "subscription-${platform.wireId}"
}

internal object ManagedSubscriptionCatalog {
    val entries =
        listOf(
            ManagedSubscriptionEntry(
                CliModelProvider.CODEX,
                "Codex",
                ProviderProtocol.OPENAI_RESPONSES,
                "https://chatgpt.com/backend-api/codex",
                "gpt-6-astra",
                "CodexLoginActivity",
            ),
            ManagedSubscriptionEntry(
                CliModelProvider.CLAUDE,
                "Claude",
                ProviderProtocol.ANTHROPIC_MESSAGES,
                "https://api.anthropic.com/v1",
                "claude-sonnet-5",
                "ClaudeLoginActivity",
            ),
            // The protocol field is storage metadata only; managed transport never falls through to an API adapter.
            ManagedSubscriptionEntry(
                CliModelProvider.ANTIGRAVITY,
                "Google Antigravity",
                ProviderProtocol.OPENAI_RESPONSES,
                "https://daily-cloudcode-pa.googleapis.com",
                "gemini-3-pro-high",
                "AntigravityLoginActivity",
            ),
            ManagedSubscriptionEntry(
                CliModelProvider.COPILOT,
                "GitHub Copilot",
                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                "https://api.githubcopilot.com",
                "claude-haiku-4.5",
                "CopilotLoginActivity",
            ),
            ManagedSubscriptionEntry(
                CliModelProvider.GROK,
                "Grok (X Premium)",
                ProviderProtocol.OPENAI_RESPONSES,
                "https://api.x.ai/v1",
                "grok-4",
                "GrokLoginActivity",
            ),
        )

    fun find(id: String): ManagedSubscriptionEntry? = entries.firstOrNull { it.id == id }
}
