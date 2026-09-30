package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Browser consent only. Neither app resume nor opening this screen initiates a login or generation. */
class AntigravityLoginActivity : Activity() {
    private data class Attempt(
        val server: CodexLoopbackServer,
        val auth: AntigravityAuth,
    )

    private val client = AntigravityClientConfig.build()
    private val guard = Any()
    private var active: Attempt? = null
    private var destroyed = false
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var vault: CliSubscriptionCredentialVault
    private lateinit var status: TextView
    private lateinit var login: Button
    private lateinit var cancel: Button
    private lateinit var logout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SubscriptionRuntimeEnvironment.initialize(this)
        vault = CliSubscriptionCredentialVault(this)
        title = getString(R.string.antigravity_title)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { setText(R.string.antigravity_warning) })
        status = TextView(this).also(column::addView)
        login =
            Button(this).apply {
                setText(R.string.antigravity_login)
                setOnClickListener { startLogin() }
            }
        cancel =
            Button(this).apply {
                setText(R.string.claude_login_cancel)
                setOnClickListener { stopLogin() }
            }
        logout =
            Button(this).apply {
                setText(R.string.claude_logout_action)
                setOnClickListener {
                    stopLogin()
                    synchronized(guard) { vault.logout(CliSubscriptionProvider.ANTIGRAVITY) }
                    render(R.string.antigravity_logged_out)
                }
            }
        listOf(login, cancel, logout).forEach(column::addView)
        SubscriptionScreen.show(this, column)
        render(
            if (vault.contains(CliSubscriptionProvider.ANTIGRAVITY)) {
                R.string.antigravity_logged_in
            } else {
                R.string.antigravity_logged_out
            },
        )
    }

    private fun startLogin() {
        val attempt =
            synchronized(guard) {
                if (active != null || destroyed || !client.configured) return
                try {
                    val server = CodexLoopbackServer.bindEphemeral()
                    Attempt(server, AntigravityAuth(AntigravityHttp())).also { active = it }
                } catch (_: java.io.IOException) {
                    render(R.string.antigravity_failed)
                    return
                }
            }
        val oauth = AntigravityOAuthProtocol.attempt(attempt.server.port)
        render(R.string.antigravity_waiting)
        attempt.server.await(oauth.state, AntigravityOAuthProtocol::callback) { result ->
            synchronized(guard) {
                if (active !== attempt || destroyed) return@await
                if (result is CodexCallbackResult.Code) {
                    worker.execute { complete(attempt, oauth, result.value) }
                } else {
                    finish(attempt, R.string.antigravity_failed)
                }
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(oauth.authorizeUrl)))
        } catch (_: RuntimeException) {
            finish(attempt, R.string.antigravity_failed)
        }
    }

    private fun complete(
        attempt: Attempt,
        oauth: CodexOAuthAttempt,
        code: String,
    ) {
        val message =
            try {
                val session = attempt.auth.exchange(oauth, code)
                synchronized(guard) {
                    if (active !== attempt || destroyed) return
                    vault.save(CliSubscriptionProvider.ANTIGRAVITY, session)
                }
                R.string.antigravity_logged_in
            } catch (_: AntigravityOnboardingRequired) {
                R.string.antigravity_onboarding
            } catch (_: Exception) {
                R.string.antigravity_failed
            }
        finish(attempt, message)
    }

    private fun finish(
        attempt: Attempt,
        message: Int,
    ) {
        attempt.auth.close()
        attempt.server.close()
        runOnUiThread {
            synchronized(guard) {
                if (active === attempt && !destroyed) {
                    active = null
                    render(message)
                }
            }
        }
    }

    private fun stopLogin() {
        val old = synchronized(guard) { active.also { active = null } }
        old?.server?.close()
        old?.auth?.close()
        if (!destroyed) render(R.string.antigravity_cancelled)
    }

    private fun render(message: Int) {
        status.setText(if (client.configured) message else R.string.antigravity_not_configured)
        val busy = synchronized(guard) { active != null }
        login.isEnabled = !busy && client.configured
        cancel.isEnabled = busy
        logout.isEnabled = !busy && vault.contains(CliSubscriptionProvider.ANTIGRAVITY)
    }

    override fun onDestroy() {
        synchronized(guard) { destroyed = true }
        stopLogin()
        worker.shutdownNow()
        super.onDestroy()
    }
}
