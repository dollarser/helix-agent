package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.Executors

class ClaudeLoginActivity : Activity() {
    private lateinit var vault: CliSubscriptionCredentialVault
    private lateinit var transport: OkHttpClaudeOAuthTransport
    private lateinit var controller: ClaudeLoginController
    private lateinit var status: TextView
    private lateinit var login: Button
    private lateinit var logout: Button
    private lateinit var cancel: Button
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var loopback: CodexLoopbackServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CliSubscriptionCredentialVault(this)
        transport = OkHttpClaudeOAuthTransport()
        controller = ClaudeLoginController(vault, transport)
        title = getString(R.string.claude_login_title)
        setContentView(buildContent())
        renderState()
    }

    override fun onDestroy() {
        loopback?.close()
        loopback = null
        transport.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun buildContent(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            val attributes = theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
            val actionBarHeight = attributes.getDimensionPixelSize(0, 0)
            attributes.recycle()
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    padding,
                    padding + actionBarHeight + insets.systemWindowInsetTop,
                    padding,
                    padding + insets.systemWindowInsetBottom,
                )
                insets
            }
            requestApplyInsets()
            addView(TextView(context).apply { setText(R.string.claude_login_warning) })
            status =
                TextView(context).also {
                    it.setPadding(0, padding, 0, padding)
                    addView(it)
                }
            login =
                Button(context).also {
                    it.setText(R.string.claude_login_action)
                    it.setOnClickListener { startLogin() }
                    addView(it)
                }
            cancel =
                Button(context).also {
                    it.setText(R.string.claude_login_cancel)
                    it.setOnClickListener { cancelLogin() }
                    addView(it)
                }
            logout =
                Button(context).also {
                    it.setText(R.string.claude_logout_action)
                    it.setOnClickListener {
                        controller.logout()
                        renderState()
                    }
                    addView(it)
                }
        }

    private fun startLogin() {
        if (loopback != null) return
        val server =
            runCatching { CodexLoopbackServer.bindEphemeral() }.getOrElse {
                status.setText(R.string.claude_login_port_error)
                return
            }
        val attempt = ClaudeOAuthProtocol.createAttempt(server.port)
        loopback = server
        status.setText(R.string.claude_login_waiting)
        renderButtons()
        server.await(attempt.state, ClaudeOAuthProtocol::parseCallback) { result ->
            if (loopback !== server) return@await
            when (result) {
                is CodexCallbackResult.Code -> completeLogin(attempt, result.value)
                else -> finishAttempt(getString(R.string.claude_login_failed))
            }
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attempt.authorizeUrl))) }
            .onFailure {
                server.close()
                finishAttempt(getString(R.string.claude_login_browser_error))
            }
    }

    private fun completeLogin(
        attempt: ClaudeOAuthAttempt,
        code: String,
    ) {
        worker.execute {
            val message =
                runCatching { controller.complete(attempt, code) }.fold(
                    onSuccess = { getString(R.string.claude_login_success, it) },
                    onFailure = ::safeFailure,
                )
            finishAttempt(message)
        }
    }

    private fun cancelLogin() {
        loopback?.close()
        loopback = null
        finishAttempt(getString(R.string.claude_login_cancelled))
    }

    private fun safeFailure(error: Throwable): String =
        when (error) {
            is ClaudeEligibilityException -> {
                getString(
                    R.string.claude_login_ineligible,
                    error.subscriptionType ?: "unknown",
                )
            }

            is ClaudeOAuthEndpointException -> {
                getString(R.string.claude_login_http_error, error.httpCode)
            }

            is IOException -> {
                getString(R.string.claude_login_network_error)
            }

            is IllegalArgumentException -> {
                getString(R.string.claude_login_protocol_error)
            }

            else -> {
                getString(R.string.claude_login_failed)
            }
        }

    private fun finishAttempt(message: String) =
        runOnUiThread {
            loopback = null
            status.text = message
            renderButtons()
        }

    private fun renderState() {
        status.setText(
            if (vault.contains(
                    CliSubscriptionProvider.CLAUDE,
                )
            ) {
                R.string.claude_login_logged_in
            } else {
                R.string.claude_login_logged_out
            },
        )
        renderButtons()
    }

    private fun renderButtons() {
        val busy = loopback != null
        val loggedIn = vault.contains(CliSubscriptionProvider.CLAUDE)
        login.isEnabled = !busy && !loggedIn
        cancel.isEnabled = busy
        logout.isEnabled = !busy && loggedIn
    }
}
