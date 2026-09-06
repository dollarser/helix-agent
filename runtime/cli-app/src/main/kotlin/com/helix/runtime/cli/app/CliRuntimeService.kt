package com.helix.runtime.cli.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelProvider
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

class CliRuntimeService : Service() {
    private lateinit var runner: CodexPayloadJobRunner
    private lateinit var oauthTransport: OkHttpCodexOAuthTransport
    private lateinit var claudeTransport: OkHttpClaudeOAuthTransport
    private lateinit var grokTransport: OkHttpGrokDeviceTransport
    private lateinit var copilotTransport: OkHttpCopilotDeviceTransport
    private val activeModel = AtomicReference<Closeable?>()

    override fun onCreate() {
        super.onCreate()
        val vault = CliSubscriptionCredentialVault(this)
        oauthTransport = OkHttpCodexOAuthTransport()
        val oauth = CodexLoginController(vault, oauthTransport)
        claudeTransport = OkHttpClaudeOAuthTransport()
        val claudeOauth = ClaudeLoginController(vault, claudeTransport)
        grokTransport = OkHttpGrokDeviceTransport()
        val grokOauth = GrokLoginController(vault, grokTransport)
        copilotTransport = OkHttpCopilotDeviceTransport()
        val copilotOauth = CopilotLoginController(vault, copilotTransport)
        runner = CodexPayloadJobRunner(
            store = CodexPayloadJobStore(filesDir),
            execute = { bytes ->
                val envelope = com.helix.runtime.cli.client.CliModelRequestCodec.decodeEnvelope(bytes)
                val request = envelope.request
                if (BuildConfig.DEBUG && request.model == "helix-fixture") {
                    return@CodexPayloadJobRunner CodexModelExecution(
                        request.model,
                        listOf(ModelEvent.TextDelta("HELIX_OK"), ModelEvent.Usage(2, 1), ModelEvent.Completed("stop")),
                    )
                }
                if (BuildConfig.DEBUG && request.model == "helix-fixture-wait") {
                    val release = java.util.concurrent.CountDownLatch(1)
                    val cancellation = Closeable { release.countDown() }
                    activeModel.set(cancellation)
                    try {
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    } finally {
                        activeModel.compareAndSet(cancellation, null)
                    }
                    return@CodexPayloadJobRunner CodexModelExecution(request.model, listOf(ModelEvent.Completed("stop")))
                }
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
                    return@CodexPayloadJobRunner try { model.use { it.run(request) } } finally { activeModel.compareAndSet(model, null) }
                }
                if (envelope.provider == CliModelProvider.COPILOT) {
                    val model = CopilotSubscriptionModel(vault, copilotOauth::refresh).also(activeModel::set)
                    return@CodexPayloadJobRunner try { model.use { it.run(request) } } finally { activeModel.compareAndSet(model, null) }
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

    override fun onBind(intent: Intent): IBinder = CliRuntimeServiceBinder(
            statusProvider = { CliEmbeddedBaseline.status(this) },
            callerVerifier = { uid -> CliCallerVerifier.verify(this, uid) },
            jobRunner = runner,
        )

    override fun onUnbind(intent: Intent): Boolean = false

    override fun onDestroy() {
        runner.close()
        activeModel.getAndSet(null)?.close()
        oauthTransport.close()
        claudeTransport.close()
        grokTransport.close()
        copilotTransport.close()
        super.onDestroy()
    }
}
