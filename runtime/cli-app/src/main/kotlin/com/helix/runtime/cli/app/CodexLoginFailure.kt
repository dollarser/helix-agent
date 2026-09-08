package com.helix.runtime.cli.app

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object CodexLoginFailure {
    fun message(
        context: android.content.Context,
        error: Throwable,
    ): String =
        when (error) {
            is CodexOAuthEndpointException -> {
                val code = error.oauthCode?.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "unspecified"
                context.getString(R.string.codex_login_http_error, error.httpCode, code)
            }

            is CodexDeviceEndpointException -> {
                context.getString(R.string.codex_device_http_error, error.stage, error.httpCode)
            }

            is CodexDeviceLoginException -> {
                context.getString(R.string.codex_device_failed, error.reason)
            }

            is CodexDeviceNetworkException -> {
                context.getString(R.string.codex_device_network_error, error.stage, error.safeNetworkCategory())
            }

            is IOException -> {
                context.getString(R.string.codex_login_network_error, error.safeNetworkCategory())
            }

            is IllegalArgumentException -> {
                context.getString(R.string.codex_login_protocol_error)
            }

            else -> {
                context.getString(R.string.codex_login_failed)
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

    fun smokeResult(
        context: android.content.Context,
        result: Result<CodexModelJobRecord>,
    ): String =
        result.fold(
            onSuccess = {
                if (it.state == CodexModelJobState.SUCCEEDED) {
                    context.getString(R.string.codex_smoke_success, it.model, CodexSubscriptionSmoke.EXPECTED_TEXT)
                } else {
                    context.getString(R.string.codex_smoke_failed, "job-${it.state.name.lowercase()}", "none")
                }
            },
            onFailure = {
                if (it is CodexSmokeException) {
                    context.getString(R.string.codex_smoke_failed, it.stage, it.httpCode?.toString() ?: "none")
                } else {
                    message(context, it)
                }
            },
        )
}
