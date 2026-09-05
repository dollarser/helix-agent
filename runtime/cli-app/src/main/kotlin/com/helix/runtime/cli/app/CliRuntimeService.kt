package com.helix.runtime.cli.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.atomic.AtomicReference

class CliRuntimeService : Service() {
    private lateinit var runner: CodexModelJobRunner
    private lateinit var oauthTransport: OkHttpCodexOAuthTransport
    private val activeSmoke = AtomicReference<CodexSubscriptionSmoke?>()

    override fun onCreate() {
        super.onCreate()
        val vault = CliSubscriptionCredentialVault(this)
        oauthTransport = OkHttpCodexOAuthTransport()
        val oauth = CodexLoginController(vault, oauthTransport)
        runner = CodexModelJobRunner(
            store = CodexModelJobStore(filesDir),
            execute = {
                val smoke = CodexSubscriptionSmoke(vault, oauth).also(activeSmoke::set)
                try {
                    smoke.use { it.run() }
                } finally {
                    activeSmoke.compareAndSet(smoke, null)
                }
            },
            cancelExecution = { activeSmoke.getAndSet(null)?.close() },
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
        activeSmoke.getAndSet(null)?.close()
        oauthTransport.close()
        super.onDestroy()
    }
}
