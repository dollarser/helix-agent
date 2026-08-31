package com.helix.provider.api.wire

import com.helix.core.model.ModelErrorCode

// Protocol-agnostic HTTP wire types (HXA-025). The public provider API exposes
// only this seam: no vendor HTTP library type leaks into the ModelProvider
// contract or the adapter modules; the OkHttp implementation (OkHttpWireClient)
// is injected by the app.

/** One outgoing HTTP exchange. Header names must be unique case-insensitively. */
public class WireRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String>,
    public val body: ByteArray?,
) {
    init {
        require(method in METHODS) { "method must be one of $METHODS" }
        require(url.isNotBlank() && url.none { it in '\u0000'..'\u001F' || it == '\u007F' }) {
            "url must be a non-blank URL without control characters"
        }
        require(body == null || method != "GET") { "GET carries no body" }
        headers.forEach { (name, value) ->
            require(name.isNotBlank() && name.none { it in '\u0000'..'\u001F' || it == '\u007F' }) {
                "header name must be a non-blank token: $name"
            }
            require(value.none { it in '\u0000'..'\u001F' || it == '\u007F' }) {
                "header value contains a control character: $name"
            }
        }
        require(
            headers.keys.size ==
                headers.keys
                    .map { it.lowercase() }
                    .toSet()
                    .size,
        ) {
            "header names must be unique case-insensitively"
        }
    }

    internal companion object {
        val METHODS = setOf("GET", "POST", "HEAD")
    }
}

/**
 * The response body of one exchange. [bytes] copies the whole body (bounded by
 * the client's cap); [forEachChunk] streams it for the SSE decoders — return
 * `false` from [onChunk] to stop reading. The consumer MUST [close] the body
 * when done (a `finally` in the provider's flow) — that releases the connection.
 * [close] is idempotent.
 */
public interface WireBody {
    /**
     * Copies the whole body (bounded by the client's cap). Suspending so the
     * implementation can hop to the IO dispatcher without blocking the
     * collector.
     */
    public suspend fun bytes(): ByteArray

    /**
     * Streams the body in chunks for the SSE decoders; the callback is
     * suspending (the caller emits into the model event flow from it). Return
     * `false` to stop reading.
     */
    public suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean)

    public fun close()
}

/** One completed HTTP exchange (status line, headers, body). */
public class WireResponse(
    public val status: Int,
    public val headers: Map<String, List<String>>,
    public val body: WireBody,
)

/**
 * The transport seam. [open] is suspending; connection-level failures
 * (DNS resolution, TLS handshake, peer reset, timeouts) propagate as
 * [java.io.IOException] — the caller maps them to
 * [com.helix.core.model.ModelErrorCode.TRANSPORT]/[com.helix.core.model.ModelErrorCode.TIMEOUT].
 */
public interface WireClient {
    public suspend fun open(request: WireRequest): WireResponse
}

/**
 * Non-2xx status → closed failure class (HXA-025 owns this mapping; the adapters'
 * KDocs name it as the transport's concern). The pair is (code, retryable):
 *
 * - 401/403 → [ModelErrorCode.AUTH] (fix the credential; not retryable);
 * - 408 → [ModelErrorCode.TIMEOUT] (gateway gave up; retryable);
 * - 429 → [ModelErrorCode.RATE_LIMITED] (retryable);
 * - 500/502/503/504 → [ModelErrorCode.SERVER_ERROR] (transient; retryable);
 * - 501 Not Implemented → [ModelErrorCode.SERVER_ERROR] (not retryable: the
 *   endpoint does not implement the API — retrying changes nothing);
 * - any other 4xx (400/404/413/422/…) → [ModelErrorCode.PROTOCOL] (the request
 *   is rejected as invalid; retrying the same request changes nothing);
 * - any other 5xx (505/…) and every other status → [ModelErrorCode.SERVER_ERROR]
 *   (fail closed: not retryable).
 */
public fun mapHttpStatus(status: Int): Pair<ModelErrorCode, Boolean> =
    when {
        status in 200..299 -> error("mapHttpStatus: 2xx is not a failure status")
        status == 401 || status == 403 -> ModelErrorCode.AUTH to false
        status == 408 -> ModelErrorCode.TIMEOUT to true
        status == 429 -> ModelErrorCode.RATE_LIMITED to true
        status == 501 -> ModelErrorCode.SERVER_ERROR to false
        status in 500..504 -> ModelErrorCode.SERVER_ERROR to true
        status in 400..499 -> ModelErrorCode.PROTOCOL to false
        status in 505..599 -> ModelErrorCode.SERVER_ERROR to false
        else -> ModelErrorCode.SERVER_ERROR to false
    }
