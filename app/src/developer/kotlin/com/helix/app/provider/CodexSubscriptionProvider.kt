package com.helix.app.provider

import android.content.Context
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import java.security.SecureRandom

/** Unified ModelProvider facade; OAuth credentials remain exclusively in the Runtime UID. */
internal class CodexSubscriptionProvider(
    private val config: ProviderConfig,
    private val jobs: SubscriptionJobExecutor,
) : ModelProvider {
    constructor(context: Context, config: ProviderConfig, platform: CliModelProvider = CliModelProvider.CODEX) : this(
        config,
        RuntimeSubscriptionJobExecutor(context.applicationContext, platform),
    )

    override val descriptor =
        ProviderDescriptor(
            config.id,
            config.displayName,
            config.protocol,
            config.model,
            config.endpoint,
        )

    override suspend fun listModels(): ModelCatalogResult = ModelCatalogResult.Listed(listOf(config.model))

    override suspend fun validateConfiguration(): ProviderCheckResult {
        val events =
            execute(
                ModelRequest(
                    config.model,
                    listOf(ModelMessage(ModelRole.USER, "Reply briefly with OK.")),
                    maxOutputTokens = 8,
                ),
            )
        val error = events.lastOrNull() as? ModelEvent.Error
        return if (error == null && events.lastOrNull()?.terminal == true) {
            ProviderCheckResult.Ok
        } else {
            ProviderCheckResult.Failed(
                error?.code ?: ModelErrorCode.PROTOCOL,
                "subscription Runtime model check failed",
                error?.retryable ?: false,
            )
        }
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            execute(request).forEach { emit(it) }
        }

    private suspend fun execute(request: ModelRequest): List<ModelEvent> =
        when (val outcome = jobs.execute(request)) {
            is CliModelJobClient.AwaitOutcome.Terminal -> {
                if (outcome.record.state == CliModelJobState.SUCCEEDED) {
                    outcome.events ?: listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
                } else {
                    listOf(
                        ModelEvent.Error(
                            errorCode(outcome.record.state),
                            outcome.record.state == CliModelJobState.INTERRUPTED,
                        ),
                    )
                }
            }

            CliModelJobClient.AwaitOutcome.TimedOut -> {
                listOf(ModelEvent.Error(ModelErrorCode.TRANSPORT, true))
            }

            is CliModelJobClient.AwaitOutcome.Unavailable -> {
                listOf(ModelEvent.Error(ModelErrorCode.TRANSPORT, true))
            }
        }

    private fun errorCode(state: CliModelJobState): ModelErrorCode =
        when (state) {
            CliModelJobState.CANCELLED -> ModelErrorCode.TRANSPORT
            CliModelJobState.INTERRUPTED -> ModelErrorCode.TRANSPORT
            else -> ModelErrorCode.PROTOCOL
        }

    private val ModelEvent.terminal: Boolean
        get() = this is ModelEvent.Completed || this is ModelEvent.Refusal || this is ModelEvent.Error
}

internal fun interface SubscriptionJobExecutor {
    suspend fun execute(request: ModelRequest): CliModelJobClient.AwaitOutcome
}

private class RuntimeSubscriptionJobExecutor(
    private val context: Context,
    private val platform: CliModelProvider,
) : SubscriptionJobExecutor {
    private val client = CliModelJobClient(CliRuntimeSupervisor(context))

    override suspend fun execute(request: ModelRequest): CliModelJobClient.AwaitOutcome {
        val ownership = currentCoroutineContext()[LocalModelCallContext]
        return runInterruptible(Dispatchers.IO) {
            val jobId = nextJobId()
            if (ownership != null) {
                val app = context.applicationContext as com.helix.app.HelixApplication
                SubscriptionJobBindingStore(app.appContainer.storage).record(ownership, jobId, request, platform)
            }
            client.submitAndAwait(jobId, request, provider = platform).also { outcome ->
                if (outcome is CliModelJobClient.AwaitOutcome.Terminal) {
                    persistBeforeAcknowledgement(ownership, outcome)
                }
            }
        }
    }

    private fun persistBeforeAcknowledgement(
        ownership: LocalModelCallContext?,
        outcome: CliModelJobClient.AwaitOutcome.Terminal,
    ) {
        if (ownership != null && outcome.record.state == CliModelJobState.SUCCEEDED) {
            val app = context.applicationContext as com.helix.app.HelixApplication
            SubscriptionResultStore(app.appContainer.storage, java.io.File(app.filesDir, "workspaces/app"))
                .persist(ownership, outcome.record, requireNotNull(outcome.events))
        }
        // Probe results are intentionally ephemeral; owned successful results are durable above.
        client.acknowledgeResult(outcome.record)
    }

    private fun nextJobId(): String =
        "job_" + ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val random = SecureRandom()
    }
}
