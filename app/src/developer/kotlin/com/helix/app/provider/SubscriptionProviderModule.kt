package com.helix.app.provider

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliRuntimeSupervisor

/** Developer-only registration seam for the non-official Codex subscription adapter. */
internal object SubscriptionProviderModule {
    const val CODEX_ID = "subscription-codex"
    const val CLAUDE_ID = "subscription-claude"
    private val capabilities = ProviderCapabilities(
        streaming = true,
        toolCalls = false,
        parallelToolCalls = false,
        vision = false,
        reasoning = false,
        jsonSchemaOutput = false,
        maxContextTokens = null,
        source = CapabilitySource.PROBED,
    )

    fun ensureRegistered(storage: HelixStorage) {
        if (runCatching { storage.providerConfigs.resolve(CLAUDE_ID) }.isFailure) {
            storage.providerConfigs.save(ProviderConfigSpec(
                id = CLAUDE_ID,
                displayName = "Claude Subscription (experimental)",
                protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
                endpoint = "https://api.anthropic.com/v1",
                model = "claude-sonnet-5",
                headersJson = "{}",
                secretAlias = ProviderFactory.NO_KEY_ALIAS,
                capabilitySnapshot = ProviderCapabilities.toJsonString(capabilities.copy(streaming = false)),
            ))
        }
        if (runCatching { storage.providerConfigs.resolve(CODEX_ID) }.isSuccess) return
        storage.providerConfigs.save(
            ProviderConfigSpec(
                id = CODEX_ID,
                displayName = "Codex Subscription (experimental)",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
                endpoint = "https://chatgpt.com/backend-api/codex",
                model = "gpt-6-astra",
                headersJson = "{}",
                secretAlias = ProviderFactory.NO_KEY_ALIAS,
                capabilitySnapshot = ProviderCapabilities.toJsonString(capabilities.copy(streaming = false)),
            ),
        )
    }

    fun create(context: Context, config: ProviderConfig): ModelProvider? =
        when (config.id) {
            CODEX_ID -> CodexSubscriptionProvider(context, config)
            CLAUDE_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.CLAUDE)
            else -> null
        }

    fun isManaged(providerId: String): Boolean = providerId == CODEX_ID || providerId == CLAUDE_ID

    suspend fun probe(config: ProviderConfig, provider: ModelProvider): ProbeOutcome? {
        if (!isManaged(config.id)) return null
        return when (val result = provider.validateConfiguration()) {
            ProviderCheckResult.Ok -> ProbeOutcome.Ok(capabilities, listOf(config.model))
            is ProviderCheckResult.Failed -> ProbeOutcome.Failed(1, result.code, result.detail, result.retryable)
        }
    }

    fun accountIntent(providerId: String = CODEX_ID): Intent =
        Intent().setComponent(
            ComponentName(CliRuntimeProtocol.RUNTIME_PACKAGE, when (providerId) {
                CODEX_ID -> CliRuntimeProtocol.CODEX_LOGIN_ACTIVITY
                CLAUDE_ID -> "com.helix.runtime.cli.app.ClaudeLoginActivity"
                else -> error("unsupported subscription provider")
            }),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun openAccount(context: Context, providerId: String): ManagedProviderAccountResult {
        if (!isManaged(providerId)) return ManagedProviderAccountResult.NOT_SUPPORTED
        if (CliRuntimeSupervisor(context).visibleUiCause() != null) {
            return ManagedProviderAccountResult.RUNTIME_UNAVAILABLE
        }
        return try {
            context.startActivity(accountIntent(providerId))
            ManagedProviderAccountResult.OPENED
        } catch (_: RuntimeException) {
            ManagedProviderAccountResult.RUNTIME_UNAVAILABLE
        }
    }
}
