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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

class CodexLoginActivity : Activity() {
    private lateinit var vault: CliSubscriptionCredentialVault
    private lateinit var transport: OkHttpCodexOAuthTransport
    private lateinit var deviceTransport: OkHttpCodexDeviceTransport
    private lateinit var controller: CodexLoginController
    private lateinit var deviceController: CodexDeviceLoginController
    private lateinit var status: TextView
    private lateinit var login: Button
    private lateinit var deviceLogin: Button
    private lateinit var openDeviceBrowser: Button
    private lateinit var copyDeviceCode: Button
    private lateinit var copyDeviceUrl: Button
    private lateinit var logout: Button
    private lateinit var cancel: Button
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var loopback: CodexLoopbackServer? = null

    @Volatile private var deviceCancellation: DeviceLoginCancellation? = null
    private var deviceUserCode: String? = null
    private var deviceVerificationUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CliSubscriptionCredentialVault(this)
        transport = OkHttpCodexOAuthTransport()
        deviceTransport = OkHttpCodexDeviceTransport()
        controller = CodexLoginController(vault, transport)
        deviceController = CodexDeviceLoginController(vault, deviceTransport)
        title = getString(R.string.codex_login_title)
        setContentView(buildContent())
        renderState()
    }

    override fun onDestroy() {
        val active = loopback
        loopback = null
        active?.close()
        deviceCancellation?.cancel()
        transport.close()
        deviceTransport.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply { setText(R.string.codex_login_warning) })
            status =
                TextView(context).also {
                    it.setPadding(0, padding, 0, padding)
                    addView(it)
                }
            login =
                Button(context).also {
                    it.setText(R.string.codex_login_action)
                    it.setOnClickListener { startLogin() }
                    addView(it)
                }
            deviceLogin =
                Button(context).also {
                    it.setText(R.string.codex_device_login_action)
                    it.setOnClickListener { startDeviceLogin() }
                    addView(it)
                }
            openDeviceBrowser =
                Button(context).also {
                    it.setText(R.string.codex_device_open_browser)
                    it.setOnClickListener { openDeviceVerification() }
                    addView(it)
                }
            copyDeviceCode =
                Button(context).also {
                    it.setText(R.string.codex_device_copy_code)
                    it.setOnClickListener { copyDeviceCode() }
                    addView(it)
                }
            copyDeviceUrl =
                Button(context).also {
                    it.setText(R.string.codex_device_copy_url)
                    it.setOnClickListener { copyDeviceUrl() }
                    addView(it)
                }
            logout =
                Button(context).also {
                    it.setText(R.string.codex_logout_action)
                    it.setOnClickListener {
                        controller.logout()
                        renderState()
                    }
                    addView(it)
                }
            cancel =
                Button(context).also {
                    it.setText(R.string.codex_login_cancel_action)
                    it.setOnClickListener {
                        loopback?.close()
                        loopback = null
                        deviceCancellation?.cancel()
                        deviceCancellation = null
                        deviceUserCode = null
                        deviceVerificationUrl = null
                        status.setText(R.string.codex_login_cancelled)
                        renderButtons()
                    }
                    addView(it)
                }
        }

    private fun startLogin() {
        if (loopback != null || deviceCancellation != null) return
        setBusy(true)
        status.setText(R.string.codex_login_preparing)
        worker.execute {
            runCatching(transport::preflight).fold(
                onSuccess = { runOnUiThread(::startLoginAfterPreflight) },
                onFailure = { finishAttempt(safeFailureMessage(it)) },
            )
        }
    }

    private fun startDeviceLogin() {
        if (loopback != null || deviceCancellation != null) return
        setBusy(true)
        status.setText(R.string.codex_device_preparing)
        val active = DeviceLoginCancellation().also { deviceCancellation = it }
        worker.execute {
            runCatching {
                val attempt = deviceController.start()
                runOnUiThread {
                    deviceUserCode = attempt.userCode
                    deviceVerificationUrl = attempt.verificationUrl
                    status.text = getString(R.string.codex_device_user_code, attempt.userCode, attempt.verificationUrl)
                    renderButtons()
                }
                deviceController.finish(attempt, active)
            }.fold(
                onSuccess = { finishAttempt(getString(R.string.codex_login_success)) },
                onFailure = { finishAttempt(safeFailureMessage(it)) },
            )
        }
    }

    private fun openDeviceVerification() {
        val url = deviceVerificationUrl ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { status.setText(R.string.codex_login_browser_error) }
    }

    private fun copyDeviceCode() {
        val code = deviceUserCode ?: return
        DeviceCodeClipboard.copy(this, getString(R.string.codex_device_clip_code_label), code)
        status.text = getString(R.string.codex_device_copied_code, code, deviceVerificationUrl)
    }

    private fun copyDeviceUrl() {
        val url = deviceVerificationUrl ?: return
        DeviceCodeClipboard.copy(this, getString(R.string.codex_device_clip_url_label), url)
        status.text = getString(R.string.codex_device_copied_url, deviceUserCode, url)
    }

    private fun startLoginAfterPreflight() {
        val server =
            try {
                CodexLoopbackServer.bind()
            } catch (_: IllegalStateException) {
                status.setText(R.string.codex_login_port_error)
                setBusy(false)
                return
            }
        val attempt = CodexOAuthProtocol.createAttempt(server.port)
        loopback = server
        status.setText(R.string.codex_login_waiting)
        server.await(attempt.state) { result ->
            if (loopback !== server) return@await
            when (result) {
                is CodexCallbackResult.Code -> completeLogin(attempt, result.value)
                is CodexCallbackResult.Rejected -> finishAttempt(getString(R.string.codex_login_failed))
                CodexCallbackResult.Ignored -> finishAttempt(getString(R.string.codex_login_failed))
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attempt.authorizeUrl)))
        } catch (_: Exception) {
            server.close()
            finishAttempt(getString(R.string.codex_login_browser_error))
        }
    }

    private fun completeLogin(
        attempt: CodexOAuthAttempt,
        code: String,
    ) {
        worker.execute {
            val result =
                runCatching {
                    controller.complete(attempt, code)
                }
            finishAttempt(
                result.fold(
                    onSuccess = { getString(R.string.codex_login_success) },
                    onFailure = ::safeFailureMessage,
                ),
            )
        }
    }

    private fun safeFailureMessage(error: Throwable): String =
        when (error) {
            is CodexOAuthEndpointException -> {
                val code = error.oauthCode?.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "unspecified"
                getString(R.string.codex_login_http_error, error.httpCode, code)
            }

            is CodexDeviceEndpointException -> {
                getString(R.string.codex_device_http_error, error.stage, error.httpCode)
            }

            is CodexDeviceLoginException -> {
                getString(R.string.codex_device_failed, error.reason)
            }

            is CodexDeviceNetworkException -> {
                getString(R.string.codex_device_network_error, error.stage, error.safeNetworkCategory())
            }

            is IOException -> {
                getString(R.string.codex_login_network_error, error.safeNetworkCategory())
            }

            is IllegalArgumentException -> {
                getString(R.string.codex_login_protocol_error)
            }

            else -> {
                getString(R.string.codex_login_failed)
            }
        }

    private fun IOException.safeNetworkCategory(): String {
        val causes = generateSequence<Throwable>(this) { it.cause }.take(8).toList()
        return when {
            causes.any { it is UnknownHostException } -> "dns"
            causes.any { it is SSLException } -> "tls"
            causes.any { it is SocketTimeoutException } -> "timeout"
            causes.any { it is ConnectException } -> "connect"
            causes.any { it is java.io.EOFException } -> "response-read"
            else -> "io"
        }
    }

    private fun finishAttempt(message: String) {
        runOnUiThread {
            loopback = null
            deviceCancellation = null
            deviceUserCode = null
            deviceVerificationUrl = null
            setBusy(false)
            status.text = message
            renderButtons()
        }
    }

    private fun renderState() {
        status.setText(
            if (vault.contains(CliSubscriptionProvider.CODEX)) {
                R.string.codex_login_logged_in
            } else {
                R.string.codex_login_logged_out
            },
        )
        renderButtons()
    }

    private fun renderButtons() {
        val loggedIn = vault.contains(CliSubscriptionProvider.CODEX)
        val busy = loopback != null || deviceCancellation != null
        login.isEnabled = !loggedIn && !busy
        deviceLogin.isEnabled = !loggedIn && !busy
        openDeviceBrowser.isEnabled = deviceCancellation != null && deviceVerificationUrl != null
        copyDeviceCode.isEnabled = deviceCancellation != null && deviceUserCode != null
        copyDeviceUrl.isEnabled = deviceCancellation != null && deviceVerificationUrl != null
        logout.isEnabled = loggedIn && !busy
        cancel.isEnabled = busy
    }

    private fun setBusy(busy: Boolean) {
        login.isEnabled = !busy
        deviceLogin.isEnabled = !busy
        openDeviceBrowser.isEnabled = false
        copyDeviceCode.isEnabled = false
        copyDeviceUrl.isEnabled = false
        logout.isEnabled = !busy
        cancel.isEnabled = busy
    }
}
