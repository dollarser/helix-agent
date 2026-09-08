package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.IOException
import java.util.concurrent.Executors

class CopilotLoginActivity : Activity() {
    private lateinit var vault: CliSubscriptionCredentialVault
    private lateinit var transport: OkHttpCopilotDeviceTransport
    private lateinit var controller: CopilotLoginController
    private lateinit var content: CopilotLoginContent
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var cancellation: CopilotLoginCancellation? = null
    private var verificationUri: String? = null
    private var userCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CliSubscriptionCredentialVault(this)
        transport = OkHttpCopilotDeviceTransport()
        controller = CopilotLoginController(vault, transport)
        title = getString(R.string.copilot_login_title)
        content =
            CopilotLoginContent(
                this,
                ::startLogin,
                ::openVerification,
                ::cancelLogin,
                {
                    controller.logout()
                    renderState()
                },
                { userCode?.let { DeviceCodeClipboard.copy(this, getString(R.string.copilot_copy_code), it) } },
                { verificationUri?.let { DeviceCodeClipboard.copy(this, getString(R.string.copilot_copy_url), it) } },
            )
        setContentView(content.root)
        renderState()
    }

    override fun onDestroy() {
        cancellation?.cancel()
        transport.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startLogin() {
        if (cancellation != null) return
        setBusy(true)
        content.status.setText(R.string.copilot_preparing)
        val active = CopilotLoginCancellation().also { cancellation = it }
        worker.execute {
            runCatching {
                val attempt = controller.start()
                runOnUiThread {
                    verificationUri = attempt.verificationUri
                    userCode = attempt.userCode
                    content.status.text =
                        getString(R.string.copilot_user_code, attempt.userCode, attempt.verificationUri)
                    renderButtons()
                }
                controller.finish(attempt, active)
            }.fold(
                onSuccess = { finishAttempt(getString(R.string.copilot_success)) },
                onFailure = { finishAttempt(safeFailure(it)) },
            )
        }
    }

    private fun openVerification() {
        val uri = verificationUri ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            .onFailure { content.status.setText(R.string.copilot_browser_error) }
    }

    private fun cancelLogin() {
        cancellation?.cancel()
        finishAttempt(getString(R.string.copilot_cancelled))
    }

    private fun safeFailure(error: Throwable): String =
        when (error) {
            is CopilotOAuthEndpointException -> getString(R.string.copilot_http_error, error.httpCode)
            is IOException -> getString(R.string.copilot_network_error)
            is IllegalArgumentException -> getString(R.string.copilot_protocol_error)
            else -> getString(R.string.copilot_failed)
        }

    private fun finishAttempt(message: String) =
        runOnUiThread {
            cancellation = null
            verificationUri = null
            userCode = null
            content.status.text = message
            renderButtons()
        }

    private fun renderState() {
        content.status.setText(
            if (vault.contains(
                    CliSubscriptionProvider.COPILOT,
                )
            ) {
                R.string.copilot_logged_in
            } else {
                R.string.copilot_logged_out
            },
        )
        renderButtons()
    }

    private fun renderButtons() {
        val busy = cancellation != null
        val loggedIn = vault.contains(CliSubscriptionProvider.COPILOT)
        content.login.isEnabled = !busy && !loggedIn
        content.openBrowser.isEnabled = busy && verificationUri != null
        content.copyCode.isEnabled = busy && userCode != null
        content.copyUrl.isEnabled = busy && verificationUri != null
        content.cancel.isEnabled = busy
        content.logout.isEnabled = !busy && loggedIn
    }

    private fun setBusy(busy: Boolean) {
        content.login.isEnabled = !busy
        content.openBrowser.isEnabled = false
        content.copyCode.isEnabled = false
        content.copyUrl.isEnabled = false
        content.cancel.isEnabled = busy
        content.logout.isEnabled = !busy
    }
}
