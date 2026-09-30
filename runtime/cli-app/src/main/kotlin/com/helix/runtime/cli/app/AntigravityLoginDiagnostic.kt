package com.helix.runtime.cli.app

/** Allowlisted diagnostics only: never include exception messages, URLs or response bodies. */
internal object AntigravityLoginDiagnostic {
    fun code(error: Exception): String =
        when (error) {
            is AntigravityHttpException -> "HTTP_${error.status}_${if (error.stage == "token") "TOKEN" else "PROJECT"}"
            is java.net.UnknownHostException -> "DNS"
            is javax.net.ssl.SSLException -> "TLS"
            is java.net.SocketTimeoutException -> "TIMEOUT"
            is java.io.IOException -> "NETWORK"
            is IllegalArgumentException -> "INVALID_RESPONSE"
            else -> "INTERNAL"
        }
}
