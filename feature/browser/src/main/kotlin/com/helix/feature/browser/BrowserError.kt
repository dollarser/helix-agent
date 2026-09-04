package com.helix.feature.browser

/**
 * A stable, user-facing reason a Helix tab shows its error view (HXA-060). [kind] is the stable,
 * locale-independent identity. The UI renders the message from [kind] via resources (HXA-069, see
 * `BrowserError.userMessage` in `com.helix.feature.browser.ui`): the failing URL is UNTRUSTED web
 * content (doc 09 §3.4), so at most a sanitized host reaches text, and the raw Chromium/WebView
 * codes never do.
 */
sealed interface BrowserError {
    val kind: BrowserErrorKind
}

enum class BrowserErrorKind {
    /** DNS / host resolution failed. */
    HOST_LOOKUP_FAILED,

    /** TCP / proxy / network-level connection failure. */
    CONNECTION_FAILED,

    /** The load exceeded its time budget. */
    TIMEOUT,

    /** TLS handshake or certificate failure; the load is cancelled, never proceeded past. */
    SSL,

    /** The URL was denied by [BrowserUrlPolicy] before it reached the WebView. */
    POLICY_BLOCKED,

    /** Any other failure (unknown Chromium/legacy code, low memory, ...). */
    UNKNOWN,
}

/** A load that the WebView attempted and that failed. [rawCode] is the dominant non-zero code. */
data class LoadError(
    override val kind: BrowserErrorKind,
    val failingUrl: String?,
    val rawCode: Int?,
) : BrowserError

/** A URL [BrowserUrlPolicy] refused; the WebView never saw it. */
data class PolicyBlockedError(
    val url: String,
    val reason: DenialReason,
) : BrowserError {
    override val kind: BrowserErrorKind
        get() = BrowserErrorKind.POLICY_BLOCKED
}

/**
 * Maps the (netError, clientError) pair delivered by
 * `WebViewClient.onReceivedError(WebResourceRequest, WebResourceError)` to a
 * [BrowserErrorKind]. [netError] is the Chromium `ERR_*` code (net_error_list.h);
 * [clientError] is the legacy `WebViewClient.ERROR_*` code. Both zero means "not a
 * load failure" and yields null — the caller must not surface an error page then.
 * An aborted load (user stop) is not an error either: it yields null here because the
 * controller clears its loading state through the same path.
 */
object BrowserErrorMapping {
    // Chromium net errors (net_error_list.h, values stable across the System WebView
    // range Helix supports). The prefix-less names below are the ERR_* constants.
    private const val ERR_TIMED_OUT = -7
    private const val ERR_NAME_NOT_RESOLVED = -105
    private const val ERR_SSL_PROTOCOL_ERROR = -107
    private const val ERR_SSL_VERSION_OR_CIPHER_MISMATCH = -113
    private const val ERR_CONNECTION_TIMED_OUT = -118
    private const val ERR_PROXY_CONNECTION_FAILED = -130
    private const val ERR_ABORTED = -3
    private const val ERR_TOO_MANY_REDIRECTS = -310
    private const val CERT_RANGE_START = -200 // CERT_COMMON_NAME_INVALID
    private const val CERT_RANGE_END = -220 // CERT_END
    private val SSL_NET_CODES =
        setOf(
            ERR_SSL_PROTOCOL_ERROR,
            ERR_SSL_VERSION_OR_CIPHER_MISMATCH,
            -110, // SSL_CLIENT_AUTH_CERT_NEEDED
            -134, // SSL_CLIENT_AUTH_PRIVATE_KEY_ACCESS_DENIED
            -135, // SSL_CLIENT_AUTH_CERT_NO_PRIVATE_KEY
            -141, // SSL_CLIENT_AUTH_SIGNATURE_FAILED
            -148, // SSL_HANDSHAKE_NOT_COMPLETED
            -149, // SSL_BAD_PEER_PUBLIC_KEY
            -150, // SSL_PINNED_KEY_NOT_IN_CERT_CHAIN
            -153, // SSL_DECRYPT_ERROR_ALERT
            -156, // SSL_SERVER_CERT_CHANGED
            -159, // SSL_UNRECOGNIZED_NAME_ALERT
            -164, // SSL_CLIENT_AUTH_CERT_BAD_FORMAT
            -167, // SSL_SERVER_CERT_BAD_FORMAT
            -172, // SSL_OBSOLETE_CIPHER
            -177, // SSL_CLIENT_AUTH_NO_COMMON_ALGORITHMS
            -181, // SSL_KEY_USAGE_INCOMPATIBLE
        )
    private val CONNECTION_NET_CODES =
        setOf(
            -15, // SOCKET_NOT_CONNECTED
            -21, // NETWORK_CHANGED
            -100, // CONNECTION_CLOSED
            -101, // CONNECTION_RESET
            -102, // CONNECTION_REFUSED
            -103, // CONNECTION_ABORTED
            -106, // INTERNET_DISCONNECTED
            -108, // ADDRESS_INVALID
            -109, // ADDRESS_UNREACHABLE
            -111, // TUNNEL_CONNECTION_FAILED
            -115, // PROXY_AUTH_UNSUPPORTED
            ERR_PROXY_CONNECTION_FAILED,
            ERR_TOO_MANY_REDIRECTS,
        )

    // Legacy WebViewClient.ERROR_* codes (clientErrorCode).
    private const val ERROR_BAD_URL = -3
    private const val ERROR_REDIRECT_LOOP = -4
    private const val ERROR_UNSUPPORTED_AUTH_SCHEME = -5
    private const val ERROR_IO = -7
    private const val ERROR_TIMEOUT = -8
    private const val ERROR_FAILED_SSL_HANDSHAKE = -9
    private const val ERROR_AUTHENTICATION = -10
    private const val ERROR_FILE_NOT_FOUND = -13
    private const val ERROR_TOO_MANY_REQUESTS = -14
    private const val ERROR_BAD_DATA = -15
    private const val ERROR_NO_HOST = -20
    private const val ERROR_UNKNOWN_PORT = -21

    /** Null when neither code signals a failure (both zero) or the load was aborted. */
    fun map(
        netError: Int,
        clientError: Int,
    ): BrowserErrorKind? {
        if (netError == ERR_ABORTED || (netError == 0 && clientError == 0)) return null
        return if (netError != 0) mapNet(netError) else mapClient(clientError)
    }

    private fun mapNet(code: Int): BrowserErrorKind =
        when {
            code == ERR_TIMED_OUT || code == ERR_CONNECTION_TIMED_OUT -> {
                BrowserErrorKind.TIMEOUT
            }

            code == ERR_NAME_NOT_RESOLVED -> {
                BrowserErrorKind.HOST_LOOKUP_FAILED
            }

            code in SSL_NET_CODES || (code in CERT_RANGE_END..CERT_RANGE_START) -> {
                BrowserErrorKind.SSL
            }

            code in CONNECTION_NET_CODES -> {
                BrowserErrorKind.CONNECTION_FAILED
            }

            else -> {
                BrowserErrorKind.UNKNOWN
            }
        }

    private fun mapClient(code: Int): BrowserErrorKind =
        when (code) {
            ERROR_TIMEOUT -> {
                BrowserErrorKind.TIMEOUT
            }

            ERROR_FAILED_SSL_HANDSHAKE -> {
                BrowserErrorKind.SSL
            }

            ERROR_BAD_URL, ERROR_NO_HOST, ERROR_UNKNOWN_PORT -> {
                BrowserErrorKind.HOST_LOOKUP_FAILED
            }

            ERROR_REDIRECT_LOOP,
            ERROR_UNSUPPORTED_AUTH_SCHEME,
            ERROR_IO,
            ERROR_AUTHENTICATION,
            ERROR_FILE_NOT_FOUND,
            ERROR_TOO_MANY_REQUESTS,
            ERROR_BAD_DATA,
            -> {
                BrowserErrorKind.CONNECTION_FAILED
            }

            else -> {
                BrowserErrorKind.UNKNOWN
            }
        }
}
