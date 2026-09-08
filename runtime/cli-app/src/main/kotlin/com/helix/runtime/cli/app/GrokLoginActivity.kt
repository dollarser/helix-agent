package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Executors

class GrokLoginActivity : Activity() {
    private lateinit var vault: CliSubscriptionCredentialVault
    private lateinit var transport: OkHttpGrokDeviceTransport
    private lateinit var controller: GrokLoginController
    private lateinit var content: GrokLoginContent
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
        content =
            GrokLoginContent(
                this,
                ::startLogin,
                ::openVerification,
                ::cancelLogin,
                {
                    controller.logout()
                    renderState()
                },
                ::copyUserCode,
                ::copyVerificationUrl,
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
        content.setBusy(true)
        content.status.setText(R.string.grok_preparing)
        val active = DeviceLoginCancellation().also { cancellation = it }
        worker.execute {
            runCatching {
                val attempt = controller.start()
                runOnUiThread {
                    verificationUri = attempt.verificationUriComplete ?: attempt.verificationUri
                    userCode = attempt.userCode
                    content.status.text = getString(R.string.grok_user_code, attempt.userCode, attempt.verificationUri)
                    renderButtons()
                }
                controller.finish(attempt, active)
            }.fold(
                onSuccess = { finishAttempt(getString(R.string.grok_success, it)) },
                onFailure = { finishAttempt(GrokLoginFailure.message(this, it)) },
            )
        }
    }

    private fun openVerification() {
        val uri = verificationUri ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
            .onFailure { content.status.setText(R.string.grok_browser_error) }
    }

    private fun copyUserCode() {
        val value = userCode ?: return
        DeviceCodeClipboard.copy(this, getString(R.string.grok_clip_code_label), value)
        content.status.text = getString(R.string.grok_copied_code, value, verificationUri)
    }

    private fun copyVerificationUrl() {
        val value = verificationUri ?: return
        DeviceCodeClipboard.copy(this, getString(R.string.grok_clip_url_label), value)
        content.status.text = getString(R.string.grok_copied_url, userCode, value)
    }

    private fun cancelLogin() {
        cancellation?.cancel()
        finishAttempt(getString(R.string.grok_cancelled))
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
            if (vault.contains(CliSubscriptionProvider.GROK)) R.string.grok_logged_in else R.string.grok_logged_out,
        )
        renderButtons()
    }

    private fun renderButtons() {
        val busy = cancellation != null
        val loggedIn = vault.contains(CliSubscriptionProvider.GROK)
        content.login.isEnabled = !busy && !loggedIn
        content.openBrowser.isEnabled = busy && verificationUri != null
        content.copyCode.isEnabled = busy && userCode != null
        content.copyUrl.isEnabled = busy && verificationUri != null
        content.cancel.isEnabled = busy
        content.logout.isEnabled = !busy && loggedIn
    }
}
