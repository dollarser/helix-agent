package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.Executors

class CodexLoginActivity : Activity() {
    private lateinit var vault: CliSubscriptionCredentialVault
    private lateinit var transport: OkHttpCodexOAuthTransport
    private lateinit var deviceTransport: OkHttpCodexDeviceTransport
    private lateinit var controller: CodexLoginController
    private lateinit var deviceController: CodexDeviceLoginController
    private lateinit var content: CodexLoginContent
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var loopback: CodexLoopbackServer? = null

    @Volatile private var deviceCancellation: DeviceLoginCancellation? = null

    @Volatile private var activeSmoke: CodexSubscriptionSmoke? = null

    private var deviceUserCode: String? = null
    private var deviceVerificationUrl: String? = null

    private val busy: Boolean
        get() = loopback != null || deviceCancellation != null || activeSmoke != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CliSubscriptionCredentialVault(this)
        transport = OkHttpCodexOAuthTransport()
        deviceTransport = OkHttpCodexDeviceTransport()
        controller = CodexLoginController(vault, transport)
        deviceController = CodexDeviceLoginController(vault, deviceTransport)
        title = getString(R.string.codex_login_title)
        content =
            CodexLoginContent(
                this,
                ::startLogin,
                ::startDeviceLogin,
                {
                    controller.logout()
                    content.renderState(
                        vault.contains(CliSubscriptionProvider.CODEX),
                        busy,
                        deviceCancellation != null,
                    )
                },
                ::runSubscriptionSmoke,
                ::cancelCurrentAttempt,
                { deviceUserCode },
                { deviceVerificationUrl },
            )
        SubscriptionScreen.show(this, content.root)
        content.renderState(
            vault.contains(CliSubscriptionProvider.CODEX),
            busy,
            deviceCancellation != null,
        )
    }

    override fun onDestroy() {
        val active = loopback
        loopback = null
        active?.close()
        deviceCancellation?.cancel()
        activeSmoke?.close()
        transport.close()
        deviceTransport.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun cancelCurrentAttempt() {
        loopback?.close()
        loopback = null
        deviceCancellation?.cancel()
        deviceCancellation = null
        activeSmoke?.close()
        activeSmoke = null
        deviceUserCode = null
        deviceVerificationUrl = null
        content.status.setText(R.string.codex_login_cancelled)
        content.renderButtons(
            vault.contains(CliSubscriptionProvider.CODEX),
            busy,
            deviceCancellation != null,
        )
    }

    private fun startLogin() {
        if (loopback != null || deviceCancellation != null) return
        content.setBusy(true, vault.contains(CliSubscriptionProvider.CODEX))
        content.status.setText(R.string.codex_login_preparing)
        worker.execute {
            runCatching(transport::preflight).fold(
                onSuccess = { runOnUiThread(::startLoginAfterPreflight) },
                onFailure = { finishAttempt(CodexLoginFailure.message(this, it)) },
            )
        }
    }

    private fun startDeviceLogin() {
        if (loopback != null || deviceCancellation != null) return
        content.setBusy(true, vault.contains(CliSubscriptionProvider.CODEX))
        content.status.setText(R.string.codex_device_preparing)
        val active = DeviceLoginCancellation().also { deviceCancellation = it }
        worker.execute {
            runCatching {
                val attempt = deviceController.start()
                runOnUiThread {
                    deviceUserCode = attempt.userCode
                    deviceVerificationUrl = attempt.verificationUrl
                    content.status.text =
                        getString(R.string.codex_device_user_code, attempt.userCode, attempt.verificationUrl)
                    content.renderButtons(
                        vault.contains(CliSubscriptionProvider.CODEX),
                        busy,
                        deviceCancellation != null,
                    )
                }
                deviceController.finish(attempt, active)
            }.fold(
                onSuccess = { finishAttempt(getString(R.string.codex_login_success)) },
                onFailure = { finishAttempt(CodexLoginFailure.message(this, it)) },
            )
        }
    }

    private fun startLoginAfterPreflight() {
        val server =
            try {
                CodexLoopbackServer.bind()
            } catch (_: IllegalStateException) {
                content.status.setText(R.string.codex_login_port_error)
                content.setBusy(false, vault.contains(CliSubscriptionProvider.CODEX))
                return
            }
        val attempt = CodexOAuthProtocol.createAttempt(server.port)
        loopback = server
        content.status.setText(R.string.codex_login_waiting)
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
                    onFailure = { CodexLoginFailure.message(this, it) },
                ),
            )
        }
    }

    private fun finishAttempt(message: String) {
        runOnUiThread {
            loopback = null
            deviceCancellation = null
            deviceUserCode = null
            deviceVerificationUrl = null
            content.setBusy(false, vault.contains(CliSubscriptionProvider.CODEX))
            content.status.text = message
            content.renderButtons(
                vault.contains(CliSubscriptionProvider.CODEX),
                busy,
                deviceCancellation != null,
            )
        }
    }

    private fun runSubscriptionSmoke() {
        if (!vault.contains(CliSubscriptionProvider.CODEX) || busy) {
            return
        }
        content.setBusy(true, vault.contains(CliSubscriptionProvider.CODEX))
        content.status.setText(R.string.codex_smoke_running)
        val smoke = CodexSubscriptionSmoke(vault, controller).also { activeSmoke = it }
        worker.execute {
            val result = runCatching { smoke.checkConnection() }
            smoke.close()
            if (activeSmoke !== smoke) return@execute
            activeSmoke = null
            finishAttempt(
                result.fold(
                    onSuccess = { getString(R.string.codex_connection_success) },
                    onFailure = {
                        CodexLoginFailure.smokeResult(this, Result.failure(it))
                    },
                ),
            )
        }
    }
}
