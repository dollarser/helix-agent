package com.helix.runtime.cli.app

import java.io.IOException

internal object GrokLoginFailure {
    fun message(
        context: android.content.Context,
        error: Throwable,
    ): String =
        when (error) {
            is GrokDeviceLoginException -> {
                when (error.reason) {
                    "ineligible_tier" -> {
                        context.getString(R.string.grok_ineligible_tier)
                    }

                    "unknown_tier" -> {
                        context.getString(R.string.grok_unknown_tier)
                    }

                    "access_denied" -> {
                        context.getString(R.string.grok_access_denied)
                    }

                    "expired_token" -> {
                        context.getString(R.string.grok_expired)
                    }

                    "missing_refresh_token", "invalid_expiry", "protocol_error" -> {
                        context.getString(R.string.grok_protocol_error_code, error.reason)
                    }

                    else -> {
                        context.getString(R.string.grok_failed_code, safeReason(error.reason))
                    }
                }
            }

            is GrokOAuthEndpointException -> {
                context.getString(R.string.grok_http_error, error.httpCode)
            }

            is IOException -> {
                context.getString(R.string.grok_network_error)
            }

            is IllegalArgumentException -> {
                context.getString(R.string.grok_protocol_error)
            }

            else -> {
                context.getString(R.string.grok_failed)
            }
        }

    private fun safeReason(reason: String): String =
        reason.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) } ?: "unspecified"
}
