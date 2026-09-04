package com.helix.tools.android

/**
 * The synchronous port the `http.fetch` tool (HXA-066) executes against.
 *
 * Production is [HttpFetchBridgeImpl] (the raw-socket transport in this module); unit tests inject
 * a fake. The port is PURE JVM (no Android types in the interface or the outcomes) and returns one
 * bounded, no-null [HttpFetchOutcome]; the tool maps it fail-closed — FETCHED / REFUSED become a
 * Completed result (a policy refusal carries a STABLE reason code, never a fake success), TIMEOUT
 * becomes [com.helix.tools.framework.ToolExecutorResult.TimedOut], and ERROR becomes a Failed
 * result.
 *
 * The security controls live in the IMPL, not the port (roadmap HXA-066, security doc 7.9): the
 * transport resolves EVERY A/AAAA/IPv4-mapped candidate, hands only the verified address set to the
 * connection, revalidates the ACTUAL peer after connect, keeps the original hostname for TLS
 * Host/SNI/certificate validation, and re-runs the whole origin/DNS/IP/scope decision on EVERY
 * redirect hop. Standard allows only the public internet; Advanced may reach a loopback/LAN host
 * ONLY through the user's pre-created exact [com.helix.core.policy.NetworkOriginScope] (a model URL
 * can never create a scope). The egress profile + scopes come from the trusted app state (an
 * [EgressPolicyProvider] seam), NEVER from the model's arguments.
 *
 * The port never throws for a network/protocol condition (only for a genuine programming error);
 * a refusal or failure is a stable outcome.
 */
interface HttpFetchBridge {
    /**
     * Performs one bounded GET/HEAD fetch of [HttpFetchRequest.url], following redirects (re-checking
     * origin/DNS/IP/scope on every hop) until [HttpFetchRequest.maxRedirects] is exhausted or a
     * non-redirect response arrives. The whole fetch is bounded by [HttpFetchRequest.deadlineMillis]
     * (an absolute epoch-millis bound the transport must honor — a timeout is
     * [HttpFetchStatus.TIMEOUT], not a hang).
     */
    fun fetch(request: HttpFetchRequest): HttpFetchOutcome
}

/**
 * One bounded fetch request. [url] is normalized fail-closed (the transport reuses the single
 * [com.helix.core.model.NormalizedEndpoint] normalizer, so userinfo / query / fragment / non-http(s)
 * schemes are refused, not followed). [method] is `GET` or `HEAD`. [maxBodyBytes] caps how much of
 * the response body is read; [deadlineMillis] is the ABSOLUTE bound (epoch millis) for the entire
 * fetch including every redirect hop.
 */
data class HttpFetchRequest(
    val url: String,
    val method: String,
    val maxBodyBytes: Long,
    val deadlineMillis: Long,
    val maxRedirects: Int = MAX_REDIRECTS,
)

/** The hard redirect-hop bound for one fetch (security doc 7.9: each hop is a fresh egress decision). */
internal const val MAX_REDIRECTS: Int = 5

enum class HttpFetchStatus {
    /** A response arrived (any final HTTP status code is carried in [HttpFetchOutcome.httpStatus]). */
    FETCHED,

    /**
     * Refused by the URL-Policy / SSRF decision (an invalid URL, a non-public rebind, a cloud
     * metadata address, a LAN/loopback host without an exact scope, …). [HttpFetchOutcome.reason]
     * is a stable code string — this is the explicit, stable "egress not allowed" signal, NEVER a
     * fabricated success and never an empty body pretending to be a fetch.
     */
    REFUSED,

    /** The [HttpFetchRequest.deadlineMillis] bound was reached (or a socket op timed out). */
    TIMEOUT,

    /** A transport/protocol failure (connect failed, malformed response, unsupported framing). */
    ERROR,
}

/**
 * Outcome of [HttpFetchBridge.fetch]. On [HttpFetchStatus.FETCHED], [finalUrl] is the URL actually
 * fetched (after redirects), [httpStatus] the final HTTP status code, [contentType] the response
 * `Content-Type` (or ""), [body] the UTF-8 decoded body (bounded to the request's byte cap),
 * [bodyBytes] how many body bytes were available (so [truncated] tells the model the body was cut),
 * [redirectCount] the number of hops followed, and [reason] "". On REFUSED the body fields are
 * empty/zero and [reason] is the stable refusal code; on TIMEOUT/ERROR [reason] is a bounded note.
 * All fields are non-null; logical absence is "" / 0 / false.
 */
data class HttpFetchOutcome(
    val status: HttpFetchStatus,
    val finalUrl: String,
    val httpStatus: Int,
    val contentType: String,
    val body: String,
    val bodyBytes: Long,
    val truncated: Boolean,
    val redirectCount: Int,
    val reason: String,
)
