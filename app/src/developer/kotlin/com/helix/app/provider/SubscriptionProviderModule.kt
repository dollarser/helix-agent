package com.helix.app.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import com.helix.runtime.cli.client.CliRuntimeVerification

/** Developer-only registration seam for the non-official Codex subscription adapter. */
internal object SubscriptionProviderModule : SubscriptionProviderIntegration {
    const val CODEX_ID = "subscription-codex"
    const val CLAUDE_ID = "subscription-claude"
    const val GROK_ID = "subscription-grok"
    const val COPILOT_ID = "subscription-copilot"
    const val ANTIGRAVITY_ID = "subscription-antigravity"
    override val providerIds: List<String> get() = ManagedSubscriptionCatalog.entries.map { it.id }

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
        ManagedSubscriptionCatalog.entries.forEach { entry ->
            val existing = storage.providerConfigs.find(entry.id)
            val spec =
                ProviderConfigSpec(
                    id = entry.id,
                    displayName = entry.label,
                    protocol = entry.protocol,
                    endpoint = entry.endpoint,
                    model =
                        existing?.model?.takeUnless { entry.id == COPILOT_ID && it == "auto" }
                            ?: entry.initialModel,
                    headersJson = "{}",
                    secretAlias = null,
                    provisioningKind = "MANAGED_ACCOUNT",
                    authKind = "MANAGED_ACCOUNT",
                    capabilitySnapshot =
                        existing?.capabilitySnapshot
                            ?: ProviderCapabilities.toJsonString(capabilities.copy(streaming = false)),
                )
            if (existing == null) {
                storage.providerConfigs.save(spec)
            } else if (existing.displayName != entry.label || existing.model != spec.model) {
                storage.providerConfigs.overwrite(spec)
            }
        }
    }

    override fun create(
        context: Context,
        config: ProviderConfig,
        imageSource: (() -> VisionImageSource)?,
    ): ModelProvider? =
        when (config.id) {
            CODEX_ID -> CodexSubscriptionProvider(context, config, imageSource = imageSource)
            ANTIGRAVITY_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.ANTIGRAVITY, imageSource)
            CLAUDE_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.CLAUDE)
            GROK_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.GROK)
            COPILOT_ID -> CodexSubscriptionProvider(context, config, CliModelProvider.COPILOT)
            else -> null
        }

    override fun isManaged(providerId: String): Boolean = ManagedSubscriptionCatalog.find(providerId) != null

    override suspend fun probe(
        config: ProviderConfig,
        provider: ModelProvider,
    ): ProbeOutcome? {
        // Google uses the shared synthetic tool/vision probe, not the text-only legacy override.
        if (!isManaged(config.id) || config.id == ANTIGRAVITY_ID) return null
        return if (config.id == CODEX_ID && provider is CodexSubscriptionProvider) {
            CodexCapabilityProbe(provider, allReasoningEfforts = false) { phase ->
                android.util.Log.d("HelixCapabilityProbe", phase)
            }.run()
        } else {
            probeText(config, provider)
        }
    }

    private suspend fun probeText(
        config: ProviderConfig,
        provider: ModelProvider,
    ): ProbeOutcome =
        when (val result = provider.validateConfiguration()) {
            ProviderCheckResult.Ok -> ProbeOutcome.Ok(capabilities, listOf(config.model))
            is ProviderCheckResult.Failed -> ProbeOutcome.Failed(1, result.code, result.detail, result.retryable)
        }

    fun accountIntent(
        context: Context,
        providerId: String = CODEX_ID,
    ): Intent =
        Intent()
            .setComponent(
                ComponentName(
                    context.packageName,
                    "com.helix.runtime.cli.app." + requireNotNull(ManagedSubscriptionCatalog.find(providerId)).activity,
                ),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Reuse the existing bounded IPC primitive; a stuck status read cannot grow threads or block resume forever.
    private val accountQueries =
        com.helix.app.localmodel
            .LocalRuntimeCalls("HelixAccountStatus", capacity = 1)

    override suspend fun accountStates(context: Context): Map<String, ManagedAccountSnapshot> =
        kotlinx.coroutines
            .withTimeoutOrNull(3000L) {
                accountQueries.call {
                    when (val result = CliRuntimeSupervisor(context, bindTimeoutMillis = 2500L).verify()) {
                        is CliRuntimeVerification.Verified -> {
                            result.status.accounts.mapKeys { "subscription-${it.key}" }.mapValues { (_, account) ->
                                ManagedAccountSnapshot(
                                    state = ManagedAccountSnapshot.State.valueOf(account.state),
                                    revision = account.revision,
                                )
                            }
                        }

                        is CliRuntimeVerification.Unavailable -> {
                            emptyMap()
                        }
                    }
                }
            }.orEmpty()

    override suspend fun openAccount(
        context: Context,
        providerId: String,
    ): ManagedProviderAccountResult {
        if (!isManaged(providerId)) return ManagedProviderAccountResult.NOT_SUPPORTED
        return if (CliRuntimeSupervisor(context).visibleUiCause() != null) {
            ManagedProviderAccountResult.RUNTIME_UNAVAILABLE
        } else {
            try {
                context.startActivity(accountIntent(context, providerId))
                ManagedProviderAccountResult.OPENED
            } catch (_: RuntimeException) {
                ManagedProviderAccountResult.RUNTIME_UNAVAILABLE
            }
        }
    }
}
