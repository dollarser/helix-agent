package com.helix.runtime.cli.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.runtime.cli.client.CliModelProvider
import java.io.Closeable

class CliRuntimeService : Service() {
    private lateinit var runner: CodexPayloadJobRunner
    private lateinit var networkForeground: SubscriptionNetworkForeground
    private val oauthTransport = lazy { OkHttpCodexOAuthTransport() }
    private val claudeTransport = lazy { OkHttpClaudeOAuthTransport() }
    private val grokTransport = lazy { OkHttpGrokDeviceTransport() }
    private val copilotTransport = lazy { OkHttpCopilotDeviceTransport() }

    override fun onCreate() {
        super.onCreate()
        SubscriptionRuntimeEnvironment.initialize(this)
        clearModelEventSpool(java.io.File(cacheDir, "model-events"))
        initializeNetworkForeground()
        val vault = CliSubscriptionCredentialVault(this)
        val oauth by lazy { CodexLoginController(vault, oauthTransport.value) }
        val claudeOauth by lazy { ClaudeLoginController(vault, claudeTransport.value) }
        val grokOauth by lazy { GrokLoginController(vault, grokTransport.value) }
        val copilotOauth by lazy { CopilotLoginController(vault, copilotTransport.value) }
        val executeModel: (
            ByteArray,
            SubscriptionCancellation,
            (List<ModelEvent>) -> Unit,
        ) -> CodexModelExecution = executeModel@{ bytes, stop, onEvents ->
            stop.checkActive()
            val envelope =
                com.helix.runtime.cli.client.CliModelRequestCodec
                    .decodeEnvelope(bytes)
            val request = envelope.request
            fixtureExecution(request, stop)?.let { return@executeModel it }
            stop.checkActive()
            networkForeground.begin()
            if (envelope.provider == CliModelProvider.CLAUDE) {
                return@executeModel stop.using(ClaudeSubscriptionModel(vault, claudeOauth::refresh)) {
                    it.run(request)
                }
            }
            if (envelope.provider == CliModelProvider.GROK) {
                return@executeModel stop.using(GrokSubscriptionModel(vault, grokOauth::refresh)) {
                    it.run(request)
                }
            }
            if (envelope.provider == CliModelProvider.COPILOT) {
                return@executeModel stop.using(CopilotSubscriptionModel(vault, copilotOauth::refresh)) {
                    it.run(request)
                }
            }
            stop.using(codexModel(vault, oauth, envelope.images)) { it.run(request, onEvents) }
        }
        runner =
            CodexPayloadJobRunner(
                store = CodexPayloadJobStore(filesDir),
                execute = { bytes, stop -> executeModel(bytes, stop) {} },
                executeStreaming = executeModel,
            )
    }

    private fun codexModel(
        vault: CliSubscriptionCredentialVault,
        oauth: CodexLoginController,
        images: List<com.helix.runtime.cli.client.CliImageSnapshot>,
    ) = CodexSubscriptionModel(vault, oauth, images = images, eventDirectory = java.io.File(cacheDir, "model-events"))

    private fun initializeNetworkForeground() {
        networkForeground =
            SubscriptionNetworkForeground(this) {
                runner.close()
            }
    }

    private fun fixtureExecution(
        request: ModelRequest,
        stop: SubscriptionCancellation,
    ): CodexModelExecution? =
        if (!BuildConfig.DEBUG) {
            null
        } else {
            when (request.model) {
                "helix-fixture" -> {
                    CodexModelExecution(
                        request.model,
                        listOf(ModelEvent.TextDelta("HELIX_OK"), ModelEvent.Usage(2, 1), ModelEvent.Completed("stop")),
                    )
                }

                "helix-fixture-wait" -> {
                    waitForFixtureCancellation(request.model, stop)
                }

                else -> {
                    null
                }
            }
        }

    private fun waitForFixtureCancellation(
        model: String,
        stop: SubscriptionCancellation,
    ): CodexModelExecution {
        val release = java.util.concurrent.CountDownLatch(1)
        val cancellation = Closeable { release.countDown() }
        stop.using(cancellation) { release.await(30, java.util.concurrent.TimeUnit.SECONDS) }
        return CodexModelExecution(model, listOf(ModelEvent.Completed("stop")))
    }

    override fun onBind(intent: Intent): IBinder =
        CliRuntimeServiceBinder(
            statusProvider = { CliEmbeddedBaseline.status(this) },
            callerVerifier = { uid -> CliCallerVerifier.verify(this, uid) },
            jobRunner = runner,
            catalogProvider = {
                networkForeground.begin()
                val vault = CliSubscriptionCredentialVault(this)
                CodexModelCatalog(vault, CodexLoginController(vault, oauthTransport.value)).fetch()
            },
        )

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        networkForeground.handle(intent)
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent): Boolean {
        networkForeground.close()
        return false
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        networkForeground.stop()
    }

    override fun onDestroy() {
        networkForeground.close()
        runner.close()
        if (oauthTransport.isInitialized()) oauthTransport.value.close()
        if (claudeTransport.isInitialized()) claudeTransport.value.close()
        if (grokTransport.isInitialized()) grokTransport.value.close()
        if (copilotTransport.isInitialized()) copilotTransport.value.close()
        super.onDestroy()
    }
}

private fun clearModelEventSpool(directory: java.io.File) {
    check(!directory.exists() || directory.deleteRecursively()) { "stale model event cleanup failed" }
}
