package com.helix.runtime.cli.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.helix.core.model.ModelEvent
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

class CliRuntimeService : Service() {
    private lateinit var runner: CodexPayloadJobRunner
    private lateinit var oauthTransport: OkHttpCodexOAuthTransport
    private val activeModel = AtomicReference<Closeable?>()

    override fun onCreate() {
        super.onCreate()
        val vault = CliSubscriptionCredentialVault(this)
        oauthTransport = OkHttpCodexOAuthTransport()
        val oauth = CodexLoginController(vault, oauthTransport)
        runner = CodexPayloadJobRunner(
            store = CodexPayloadJobStore(filesDir),
            execute = { bytes ->
                val request = com.helix.runtime.cli.client.CliModelRequestCodec.decode(bytes)
                if (BuildConfig.DEBUG && request.model == "helix-fixture") {
                    return@CodexPayloadJobRunner CodexModelExecution(
                        request.model,
                        listOf(ModelEvent.TextDelta("HELIX_OK"), ModelEvent.Usage(2, 1), ModelEvent.Completed("stop")),
                    )
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
        super.onDestroy()
    }
}
