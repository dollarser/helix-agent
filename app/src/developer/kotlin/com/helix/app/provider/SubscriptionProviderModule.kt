package com.helix.app.provider

import android.content.ComponentName
import android.content.Context
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
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliRuntimeSupervisor

/** Developer-only registration seam for the non-official Codex subscription adapter. */
internal object SubscriptionProviderModule : SubscriptionProviderIntegration {
    const val CODEX_ID = "subscription-codex"
    const val CLAUDE_ID = "subscription-claude"
    const val GROK_ID = "subscription-grok"
    const val COPILOT_ID = "subscription-copilot"
    private val capabilities =
        ProviderCapabilities(
            streaming = true,
            toolCalls = false,
            parallelToolCalls = false,
            vision = false,
            reasoning = false,
            jsonSchemaOutput = false,
            maxContextTokens = null,
            source = CapabilitySource.PROBED,
        )

    override fun recoverInterruptedResult(
        context: Context,
        storage: HelixStorage,
        turnId: String,
        modelCallId: String,
        localOnly: Boolean,
    ): SubscriptionRecoveredOutput? =
        SubscriptionResultRecovery(context, storage).recover(turnId, modelCallId, localOnly)

    override fun inspectInterruptedJob(
        context: Context,
        storage: HelixStorage,
        turnId: String,
        modelCallId: String,
        stop: Boolean,
    ): SubscriptionRecoveryStatus =
        SubscriptionJobRecovery(
            storage,
            com.helix.runtime.cli.client
                .CliModelJobClient(CliRuntimeSupervisor(context)),
        ).inspect(turnId, modelCallId, stop)

    override fun ensureRegistered(storage: HelixStorage) {
        val existingCopilot = runCatching { storage.providerConfigs.resolve(COPILOT_ID) }.getOrNull()
        if (existingCopilot == null || existingCopilot.model == "auto") {
            val spec =
                ProviderConfigSpec(
                    id = COPILOT_ID,
                    displayName = "Copilot Subscription (experimental)",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = "https://api.githubcopilot.com",
                    model = "claude-haiku-4.5",
                    headersJson = "{}",
                    secretAlias = ProviderFactory.NO_KEY_ALIAS,
                    capabilitySnapshot = ProviderCapabilities.toJsonString(capabilities.copy(streaming = false)),
                )
            // Replace only the invalid pre-release default; preserve every other configured model.
            if (existingCopilot == null) storage.providerConfigs.save(spec) else storage.providerConfigs.overwrite(spec)
        }
        if (runCatching { storage.providerConfigs.resolve(GROK_ID) }.isFailure) {
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = GROK_ID,
                    displayName = "Grok Subscription (experimental)",
                    protocol = ProviderProtocol.OPENAI_RESPONSES,
                    endpoint = "https://api.x.ai/v1",
                    model = "grok-4",
                    headersJson = "{}",
                    secretAlias = ProviderFactory.NO_KEY_ALIAS,
                    capabilitySnapshot = ProviderCapabilities.toJsonString(capabilities.copy(streaming = false)),
                ),
            )
        }
        if (runCatching { storage.providerConfigs.resolve(CLAUDE_ID) }.isFailure) {
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = CLAUDE_ID,
                    displayName = "Claude Subscription (experimental)",
                    protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
                    endpoint = "https://api.anthropic.com/v1",
                    model = "claude-sonnet-5",
                    headersJson = "{}",
                    secretAlias = ProviderFactory.NO_KEY_ALIAS,
                    capabilitySnapshot = ProviderCapabilities.toJsonString(capabilities.copy(streaming = false)),
                ),
            )
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

    override fun create(
        context: Context,
        config: ProviderConfig,
    ): ModelProvider? =
        when (config.id) {
            CODEX_ID -> CodexSubscriptionProvider(context, config)
            CLAUDE_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.CLAUDE)
            GROK_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.GROK)
            COPILOT_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.COPILOT)
            else -> null
        }

    override fun isManaged(providerId: String): Boolean = providerId in setOf(CODEX_ID, CLAUDE_ID, GROK_ID, COPILOT_ID)

    override suspend fun probe(
        config: ProviderConfig,
        provider: ModelProvider,
    ): ProbeOutcome? {
        if (!isManaged(config.id)) return null
        return when (val result = provider.validateConfiguration()) {
            ProviderCheckResult.Ok -> ProbeOutcome.Ok(capabilities, listOf(config.model))
            is ProviderCheckResult.Failed -> ProbeOutcome.Failed(1, result.code, result.detail, result.retryable)
        }
    }

    fun accountIntent(providerId: String = CODEX_ID): Intent =
        Intent()
            .setComponent(
                ComponentName(
                    CliRuntimeProtocol.RUNTIME_PACKAGE,
                    when (providerId) {
                        CODEX_ID -> CliRuntimeProtocol.CODEX_LOGIN_ACTIVITY
                        CLAUDE_ID -> "com.helix.runtime.cli.app.ClaudeLoginActivity"
                        GROK_ID -> "com.helix.runtime.cli.app.GrokLoginActivity"
                        COPILOT_ID -> "com.helix.runtime.cli.app.CopilotLoginActivity"
                        else -> error("unsupported subscription provider")
                    },
                ),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override suspend fun openAccount(
        context: Context,
        providerId: String,
    ): ManagedProviderAccountResult {
        if (!isManaged(providerId)) return ManagedProviderAccountResult.NOT_SUPPORTED
        return if (CliRuntimeSupervisor(context).visibleUiCause() != null) {
            ManagedProviderAccountResult.RUNTIME_UNAVAILABLE
        } else {
            try {
                context.startActivity(accountIntent(providerId))
                ManagedProviderAccountResult.OPENED
            } catch (_: RuntimeException) {
                ManagedProviderAccountResult.RUNTIME_UNAVAILABLE
            }
        }
    }
}
