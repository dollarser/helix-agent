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

class GrokLoginActivity : Activity() {
    private lateinit var vault: CliSubscriptionCredentialVault
    private lateinit var transport: OkHttpGrokDeviceTransport
    private lateinit var controller: GrokLoginController
    private lateinit var status: TextView
    private lateinit var login: Button
    private lateinit var openBrowser: Button
    private lateinit var copyCode: Button
    private lateinit var copyUrl: Button
    private lateinit var cancel: Button
    private lateinit var logout: Button
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var cancellation: DeviceLoginCancellation? = null
    private var verificationUri: String? = null
    private var userCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CliSubscriptionCredentialVault(this)
        transport = OkHttpGrokDeviceTransport()
        controller = GrokLoginController(vault, transport)
        title = getString(R.string.grok_login_title)
        setContentView(buildContent())
        renderState()
    }

    override fun onDestroy() {
        cancellation?.cancel()
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
            addView(TextView(context).apply { setText(R.string.grok_login_warning) })
            status =
                TextView(context).also {
                    it.setPadding(0, padding, 0, padding)
                    addView(it)
                }
            login =
                Button(context).also {
                    it.setText(R.string.grok_login_action)
                    it.setOnClickListener { startLogin() }
                    addView(it)
                }
            openBrowser =
                Button(context).also {
                    it.setText(R.string.grok_open_browser)
                    it.setOnClickListener { openVerification() }
                    addView(it)
                }
            copyCode =
                Button(context).also {
                    it.setText(R.string.grok_copy_code)
                    it.setOnClickListener { copyUserCode() }
                    addView(it)
                }
            copyUrl =
                Button(context).also {
                    it.setText(R.string.grok_copy_url)
                    it.setOnClickListener { copyVerificationUrl() }
                    addView(it)
                }
            cancel =
                Button(context).also {
                    it.setText(R.string.grok_cancel)
                    it.setOnClickListener { cancelLogin() }
                    addView(it)
                }
            logout =
                Button(context).also {
                    it.setText(R.string.grok_logout)
                    it.setOnClickListener {
                        controller.logout()
                        renderState()
                    }
                    addView(it)
                }
        }

    private fun startLogin() {
        if (cancellation != null) return
        setBusy(true)
        status.setText(R.string.grok_preparing)
        val active = DeviceLoginCancellation().also { cancellation = it }
        worker.execute {
            runCatching {
                val attempt = controller.start()
                runOnUiThread {
                    verificationUri = attempt.verificationUriComplete ?: attempt.verificationUri
                    userCode = attempt.userCode
                    status.text = getString(R.string.grok_user_code, attempt.userCode, attempt.verificationUri)
                    renderButtons()
                }
                controller.finish(attempt, active)
            }.fold(
                onSuccess = { finishAttempt(getString(R.string.grok_success, it)) },
                onFailure = { finishAttempt(safeFailure(it)) },
            )
        }
    }

    private fun openVerification() {
        val uri = verificationUri ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            .onFailure { status.setText(R.string.grok_browser_error) }
    }

    private fun copyUserCode() {
        val value = userCode ?: return
        DeviceCodeClipboard.copy(this, getString(R.string.grok_clip_code_label), value)
        status.text = getString(R.string.grok_copied_code, value, verificationUri)
    }

    private fun copyVerificationUrl() {
        val value = verificationUri ?: return
        DeviceCodeClipboard.copy(this, getString(R.string.grok_clip_url_label), value)
        status.text = getString(R.string.grok_copied_url, userCode, value)
    }

    private fun cancelLogin() {
        cancellation?.cancel()
        finishAttempt(getString(R.string.grok_cancelled))
    }

    private fun safeFailure(error: Throwable): String =
        when (error) {
            is GrokDeviceLoginException -> {
                when (error.reason) {
                    "ineligible_tier" -> {
                        getString(R.string.grok_ineligible_tier)
                    }

                    "unknown_tier" -> {
                        getString(R.string.grok_unknown_tier)
                    }

                    "access_denied" -> {
                        getString(R.string.grok_access_denied)
                    }

                    "expired_token" -> {
                        getString(R.string.grok_expired)
                    }

                    "missing_refresh_token", "invalid_expiry", "protocol_error" -> {
                        getString(R.string.grok_protocol_error_code, error.reason)
                    }

                    else -> {
                        getString(R.string.grok_failed_code, safeReason(error.reason))
                    }
                }
            }

            is GrokOAuthEndpointException -> {
                getString(R.string.grok_http_error, error.httpCode)
            }

            is IOException -> {
                getString(R.string.grok_network_error)
            }

            is IllegalArgumentException -> {
                getString(R.string.grok_protocol_error)
            }

            else -> {
                getString(R.string.grok_failed)
            }
        }

    private fun safeReason(reason: String): String =
        reason.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "unspecified"

    private fun finishAttempt(message: String) =
        runOnUiThread {
            cancellation = null
            verificationUri = null
            userCode = null
            status.text = message
            renderButtons()
        }

    private fun renderState() {
        status.setText(
            if (vault.contains(CliSubscriptionProvider.GROK)) R.string.grok_logged_in else R.string.grok_logged_out,
        )
        renderButtons()
    }

    private fun renderButtons() {
        val busy = cancellation != null
        val loggedIn = vault.contains(CliSubscriptionProvider.GROK)
        login.isEnabled = !busy && !loggedIn
        openBrowser.isEnabled = busy && verificationUri != null
        copyCode.isEnabled = busy && userCode != null
        copyUrl.isEnabled = busy && verificationUri != null
        cancel.isEnabled = busy
        logout.isEnabled = !busy && loggedIn
    }

    private fun setBusy(busy: Boolean) {
        login.isEnabled = !busy
        openBrowser.isEnabled = false
        copyCode.isEnabled = false
        copyUrl.isEnabled = false
        cancel.isEnabled = busy
        logout.isEnabled = !busy
    }
}
