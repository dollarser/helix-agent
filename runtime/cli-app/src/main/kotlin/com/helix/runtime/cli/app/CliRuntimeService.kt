package com.helix.runtime.cli.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.runtime.cli.client.CliModelProvider
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

class CliRuntimeService : Service() {
    private lateinit var runner: CodexPayloadJobRunner
    private val oauthTransport = lazy { OkHttpCodexOAuthTransport() }
    private val claudeTransport = lazy { OkHttpClaudeOAuthTransport() }
    private val grokTransport = lazy { OkHttpGrokDeviceTransport() }
    private val copilotTransport = lazy { OkHttpCopilotDeviceTransport() }
    private val activeModel = AtomicReference<Closeable?>()

    override fun onCreate() {
        super.onCreate()
        val vault = CliSubscriptionCredentialVault(this)
        val oauth by lazy { CodexLoginController(vault, oauthTransport.value) }
        val claudeOauth by lazy { ClaudeLoginController(vault, claudeTransport.value) }
        val grokOauth by lazy { GrokLoginController(vault, grokTransport.value) }
        val copilotOauth by lazy { CopilotLoginController(vault, copilotTransport.value) }
        runner =
            CodexPayloadJobRunner(
                store = CodexPayloadJobStore(filesDir),
                execute = { bytes ->
                    val envelope =
                        com.helix.runtime.cli.client.CliModelRequestCodec
                            .decodeEnvelope(bytes)
                    val request = envelope.request
                    fixtureExecution(request)?.let { return@CodexPayloadJobRunner it }
                    if (envelope.provider == CliModelProvider.CLAUDE) {
                        val model = ClaudeSubscriptionModel(vault, claudeOauth::refresh).also(activeModel::set)
                        return@CodexPayloadJobRunner try {
                            model.use { it.run(request) }
                        } finally {
                            activeModel.compareAndSet(model, null)
                        }
                    }
                    if (envelope.provider == CliModelProvider.GROK) {
                        val model = GrokSubscriptionModel(vault, grokOauth::refresh).also(activeModel::set)
                        return@CodexPayloadJobRunner try {
                            model.use { it.run(request) }
                        } finally {
                            activeModel.compareAndSet(model, null)
                        }
                    }
                    if (envelope.provider == CliModelProvider.COPILOT) {
                        val model = CopilotSubscriptionModel(vault, copilotOauth::refresh).also(activeModel::set)
                        return@CodexPayloadJobRunner try {
                            model.use { it.run(request) }
                        } finally {
                            activeModel.compareAndSet(model, null)
                        }
                    }
                    val model = CodexSubscriptionModel(vault, oauth).also(activeModel::set)
                    try {
                        model.use { it.run(request) }
                    } finally {
                        activeModel.compareAndSet(model, null)
                    }
                },
                cancelExecution = { activeModel.getAndSet(null)?.close() },
            )
    }

    private fun fixtureExecution(request: ModelRequest): CodexModelExecution? =
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
                    waitForFixtureCancellation(request.model)
                }

                else -> {
                    null
                }
            }
        }

    private fun waitForFixtureCancellation(model: String): CodexModelExecution {
        val release = java.util.concurrent.CountDownLatch(1)
        val cancellation = Closeable { release.countDown() }
        activeModel.set(cancellation)
        try {
            release.await(30, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            activeModel.compareAndSet(cancellation, null)
        }
        return CodexModelExecution(model, listOf(ModelEvent.Completed("stop")))
    }

    override fun onBind(intent: Intent): IBinder =
        CliRuntimeServiceBinder(
            statusProvider = { CliEmbeddedBaseline.status(this) },
            callerVerifier = { uid -> CliCallerVerifier.verify(this, uid) },
            jobRunner = runner,
        )

    override fun onUnbind(intent: Intent): Boolean = false

    override fun onDestroy() {
        runner.close()
        activeModel.getAndSet(null)?.close()
        if (oauthTransport.isInitialized()) oauthTransport.value.close()
        if (claudeTransport.isInitialized()) claudeTransport.value.close()
        if (grokTransport.isInitialized()) grokTransport.value.close()
        if (copilotTransport.isInitialized()) copilotTransport.value.close()
        super.onDestroy()
    }
}
