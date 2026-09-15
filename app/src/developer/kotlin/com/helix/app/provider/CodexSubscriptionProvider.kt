package com.helix.app.provider

import android.content.Context
import com.helix.app.HelixApplication
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import com.helix.runtime.cli.client.CliImageSnapshot
import com.helix.runtime.cli.client.CliModelCatalog
import com.helix.runtime.cli.client.CliModelCatalogClient
import com.helix.runtime.cli.client.CliModelInfo
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import java.security.SecureRandom

/** Unified ModelProvider facade; OAuth credentials remain exclusively in the Runtime UID. */
internal class CodexSubscriptionProvider(
    private val config: ProviderConfig,
    private val jobs: SubscriptionJobExecutor,
    private val catalogLoader: (suspend () -> CliModelCatalog)? = null,
) : ModelProvider,
    SubscriptionConnectionProvider {
    constructor(
        context: Context,
        config: ProviderConfig,
        platform: CliModelProvider = CliModelProvider.CODEX,
        imageSource: (() -> VisionImageSource)? = null,
    ) : this(
        config,
        RuntimeSubscriptionJobExecutor(context.applicationContext, platform, imageSource),
        if (platform == CliModelProvider.CODEX) {
            { runInterruptible(Dispatchers.IO) { CliModelCatalogClient(CliRuntimeSupervisor(context)).fetch() } }
        } else {
            null
        },
    )

    override val descriptor =
        ProviderDescriptor(
            config.id,
            config.displayName,
            config.protocol,
            config.model,
            config.endpoint,
        )

    private var catalog: CliModelCatalog? = null

    private suspend fun loadCatalog(): CliModelCatalog? {
        val result = catalog ?: catalogLoader?.invoke()
        if (result is CliModelCatalog.Listed) catalog = result
        return result
    }

    override suspend fun connectionCatalog(): ModelCatalogResult? = if (catalogLoader != null) listModels() else null

    override suspend fun listModels(): ModelCatalogResult =
        when (val result = loadCatalog()) {
            is CliModelCatalog.Listed -> {
                ModelCatalogResult.Listed(result.models.map { it.id })
            }

            is CliModelCatalog.Failed -> {
                ModelCatalogResult.Failed(
                    result.code,
                    "subscription catalog unavailable",
                    result.retryable,
                )
            }

            null -> {
                ModelCatalogResult.Listed(listOf(config.model))
            }
        }

    override suspend fun contextWindow(model: String): Long? =
        (loadCatalog() as? CliModelCatalog.Listed)?.models?.find { it.id == model }?.contextWindow

    override suspend fun modelMetadata(): Map<String, ModelMetadata> =
        (loadCatalog() as? CliModelCatalog.Listed)?.models.orEmpty().associate { info ->
            info.id to
                ModelMetadata(
                    info.reasoningEfforts?.map(ReasoningEffort::fromWire),
                    info.vision,
                    info.contextWindow,
                )
        }

    suspend fun modelInfo(): CliModelInfo? =
        (loadCatalog() as? CliModelCatalog.Listed)?.models?.find { it.id == config.model }

    override suspend fun validateConfiguration(): ProviderCheckResult {
        val events =
            eventsFor(
                jobs.execute(
                    ModelRequest(
                        config.model,
                        listOf(ModelMessage(ModelRole.USER, "Reply briefly with OK.")),
                        maxOutputTokens = 8,
                    ),
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

    var observedIncrementalDelivery = false
        private set

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        channelFlow {
            val delivered = ArrayList<ModelEvent>()
            val outcome =
                jobs.execute(request) { chunk ->
                    observedIncrementalDelivery = true
                    delivered.addAll(chunk)
                    chunk.forEach { trySendBlocking(it).getOrThrow() }
                }
            val events = eventsFor(outcome)
            val remaining = if (events.take(delivered.size) == delivered) events.drop(delivered.size) else events
            remaining.forEach { send(it) }
        }

    private fun eventsFor(outcome: CliModelJobClient.AwaitOutcome): List<ModelEvent> =
        when (outcome) {
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

    suspend fun execute(
        request: ModelRequest,
        onProgress: (List<ModelEvent>) -> Unit,
    ): CliModelJobClient.AwaitOutcome = execute(request)
}

private class RuntimeSubscriptionJobExecutor(
    private val context: Context,
    private val platform: CliModelProvider,
    private val imageSource: (() -> VisionImageSource)?,
) : SubscriptionJobExecutor {
    private val client = CliModelJobClient(CliRuntimeSupervisor(context))

    override suspend fun execute(request: ModelRequest): CliModelJobClient.AwaitOutcome = execute(request) {}

    override suspend fun execute(
        request: ModelRequest,
        onProgress: (List<ModelEvent>) -> Unit,
    ): CliModelJobClient.AwaitOutcome {
        val ownership = currentCoroutineContext()[LocalModelCallContext]
        return runInterruptible(Dispatchers.IO) {
            val images =
                request.messages.flatMap { it.images }.distinct().map { image ->
                    val loaded =
                        requireNotNull(
                            imageSource,
                        ) { "subscription image source unavailable" }.invoke().load(image.ref)
                    require(loaded.mediaType == image.mediaType)
                    CliImageSnapshot(image, loaded.base64)
                }
            val jobId = nextJobId()
            if (ownership != null) {
                val app = context.applicationContext as HelixApplication
                SubscriptionJobBindingStore(
                    app.appContainer.storage,
                ).record(ownership, jobId, request, platform, images)
            }
            val completed =
                client.submitAndAwait(
                    jobId,
                    request,
                    provider = platform,
                    images = images,
                    onProgress = onProgress,
                )
            completed.also { outcome ->
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
            val app = context.applicationContext as HelixApplication
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
