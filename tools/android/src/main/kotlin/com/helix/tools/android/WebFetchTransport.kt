package com.helix.tools.android

import com.helix.core.model.CancelSignal
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.policy.SsrfAddressPolicy
import com.helix.core.policy.SsrfCheckResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A fetched document is data, not a local file, browser session or instruction. */
internal data class WebFetchPage(
    val url: String,
    val status: Int,
    val mediaType: String,
    val charset: String?,
    val bytes: ByteArray,
    val truncated: Boolean,
    val redirects: Int,
)

internal fun interface WebFetchTransport {
    fun fetch(
        url: String,
        deadline: Long,
        cancel: CancelSignal,
    ): WebFetchPage
}

internal class WebFetchFailure(
    val code: String,
) : IOException(code)

/** Dedicated web URL parsing does not relax the provider/MCP endpoint normalizer. */
@Suppress("ThrowsCount") // Distinct URL failures are intentional, ordered admission guards.
internal fun webFetchUrl(raw: String): HttpUrl {
    if (!isWebFetchReference(raw)) {
        throw WebFetchFailure("invalid-url")
    }
    if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
        throw WebFetchFailure("invalid-url")
    }
    val rawAuthority =
        raw
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    if (rawAuthority.isEmpty()) throw WebFetchFailure("invalid-url")
    val url = raw.toHttpUrlOrNull() ?: throw WebFetchFailure("invalid-url")
    if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() ||
        '@' in
        raw
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    ) {
        throw WebFetchFailure("credentials-in-url")
    }
    // OkHttp accepts hosts (for example underscores) that our origin policy cannot represent.
    // Validate before DNS and surface a stable failure, never the constructor's raw exception.
    webFetchEndpoint(url)
    return url.newBuilder().fragment(null).build().also {
        if (it.toString().length > WEB_URL_LIMIT) throw WebFetchFailure("url-too-long")
    }
}

private fun isWebFetchReference(value: String): Boolean {
    val forbiddenCharacter = value.any { it <= ' ' || it == '\u007f' || it == '\\' }
    return value.length in 1..WEB_URL_LIMIT && !forbiddenCharacter
}

private fun webFetchEndpoint(url: HttpUrl): NormalizedEndpoint =
    try {
        NormalizedEndpoint(url.scheme, url.host, url.port, "")
    } catch (_: IllegalArgumentException) {
        throw WebFetchFailure("invalid-url")
    }

@Suppress("ThrowsCount") // Preserve distinct raw-reference, credentials and downgrade failures.
internal fun webFetchRedirect(base: HttpUrl, location: String): HttpUrl {
    if (!isWebFetchReference(location)) {
        throw WebFetchFailure("invalid-redirect")
    }
    // Resolve only after checking raw authority: resolution erases empty userinfo and controls.
    val authority =
        location
            .substringAfter("://", location.removePrefix("//"))
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    if ('@' in authority && (location.startsWith("//") || "://" in location)) {
        throw WebFetchFailure("credentials-in-url")
    }
    val next = base.resolve(location) ?: throw WebFetchFailure("invalid-redirect")
    if (base.isHttps && !next.isHttps) throw WebFetchFailure("https-downgrade")
    return webFetchUrl(next.toString())
}

private fun checkWebFetchEncoding(
    values: List<String>,
    allowGzip: Boolean,
) {
    if (values.isEmpty()) return
    // Check every field before OkHttp's bridge can remove Content-Encoding after gzip decoding.
    // A single identity or gzip is supported; stacked/duplicate/malformed codings are not.
    val encoding = values.singleOrNull()?.trim()
    val supported =
        encoding.equals("identity", ignoreCase = true) ||
            (allowGzip && encoding.equals("gzip", ignoreCase = true))
    if (!supported) throw WebFetchFailure("unsupported-content-encoding")
}

internal fun checkWebFetchActive(
    deadline: Long,
    cancel: CancelSignal,
) {
    if (cancel.isCancelled() || Thread.currentThread().isInterrupted) throw WebFetchFailure("cancelled")
    if (System.currentTimeMillis() >= deadline) throw WebFetchFailure("timed-out")
}

/** No cookies, authentication, proxies, automatic redirects or automatic connection retries. */
internal class PinnedWebFetchTransport(
    private val policyProvider: EgressPolicyProvider,
    private val resolver: AddressResolver = RealAddressResolver(),
) : WebFetchTransport {
    override fun fetch(
        url: String,
        deadline: Long,
        cancel: CancelSignal,
    ): WebFetchPage {
        val ceiling = policyProvider.current().let { it.copy(lanScopes = it.lanScopes.toSet()) }
        var target = webFetchUrl(url)
        for (hop in 0..WEB_REDIRECT_LIMIT) {
            checkWebFetchActive(deadline, cancel)
            val response = fetchHop(target, ceiling, deadline, cancel, hop)
            if (response.location == null) return response.page
            if (hop == WEB_REDIRECT_LIMIT) throw WebFetchFailure("redirect-limit")
            target = webFetchRedirect(target, response.location)
        }
        throw WebFetchFailure("redirect-limit")
    }

    /** Fixed-provider JSON POST uses the same connection-time governance, never redirects or retries. */
    @Suppress("ThrowsCount") // Distinct protocol failures are stable, bounded admission checks.
    internal fun postJson(
        request: Request,
        deadline: Long,
        cancel: CancelSignal,
        beforeSend: () -> Unit = {},
    ): ByteArray {
        checkWebFetchActive(deadline, cancel)
        val url = request.url
        val ceiling = policyProvider.current().let { it.copy(lanScopes = it.lanScopes.toSet()) }
        val endpoint = webFetchEndpoint(url)
        val pinned = resolver.resolve(url.host).map { it.copyOf() }
        checkWebFetchActive(deadline, cancel)
        authorize(pinned, endpoint, ceiling)
        val client = createClient(HopTarget(url, endpoint, pinned, ceiling), deadline, cancel, beforeSend)
        val call = client.newCall(request)
        val watch =
            cancellationWatch.scheduleWithFixedDelay({
                if (cancel.isCancelled() || System.currentTimeMillis() >= deadline) call.cancel()
            }, 0, CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                checkWebFetchActive(deadline, cancel)
                if (!response.isSuccessful) throw WebFetchFailure("provider-http-${response.code}")
                if (response.body.contentType()?.subtype != "json") throw WebFetchFailure("invalid-provider-response")
                checkWebFetchEncoding(response.headers.values("Content-Encoding"), allowGzip = false)
                val bytes = readBody(response.body.source(), deadline, cancel)
                if (bytes.size > WEB_BODY_LIMIT) throw WebFetchFailure("provider-response-too-large")
                checkWebFetchActive(deadline, cancel)
                authorize(pinned, endpoint, ceiling)
                bytes
            }
        } finally {
            watch.cancel(false)
            call.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun authorize(
        addresses: List<ByteArray>,
        endpoint: NormalizedEndpoint,
        ceiling: EgressPolicy,
    ) {
        for (policy in listOf(ceiling, policyProvider.current())) {
            val decision = SsrfAddressPolicy.check(addresses, policy.profile, policy.lanScopes, endpoint)
            if (decision is SsrfCheckResult.Denied) throw WebFetchFailure("network-${decision.code.name.lowercase()}")
        }
    }

    private fun fetchHop(
        url: HttpUrl,
        ceiling: EgressPolicy,
        deadline: Long,
        cancel: CancelSignal,
        hop: Int,
    ): Hop {
        val endpoint = webFetchEndpoint(url)
        checkWebFetchActive(deadline, cancel)
        val pinned = resolver.resolve(url.host).map { it.copyOf() }
        checkWebFetchActive(deadline, cancel)
        authorize(pinned, endpoint, ceiling)
        val target = HopTarget(url, endpoint, pinned, ceiling)
        val client = createClient(target, deadline, cancel)
        val call =
            client.newCall(
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "Helix/1")
                    .build(),
            )
        // Cancellation closes active sockets. A platform DNS call may finish later; the executor
        // keeps its physical slot until it returns, and the post-DNS guard prevents any HTTP send.
        val watch =
            cancellationWatch.scheduleWithFixedDelay({
                if (cancel.isCancelled() || System.currentTimeMillis() >= deadline) call.cancel()
            }, 0, CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                checkWebFetchActive(deadline, cancel)
                val page = readPage(response, url, deadline, cancel, hop)
                checkWebFetchActive(deadline, cancel)
                authorize(pinned, endpoint, ceiling)
                page
            }
        } finally {
            watch.cancel(false)
            call.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun createClient(
        target: HopTarget,
        deadline: Long,
        cancel: CancelSignal,
        beforeSend: () -> Unit = {},
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout((deadline - System.currentTimeMillis()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
            .dns { host ->
                checkWebFetchActive(deadline, cancel)
                if (host != target.url.host) throw WebFetchFailure("unexpected-host")
                authorize(target.pinned, target.endpoint, target.ceiling)
                target.pinned.map { InetAddress.getByAddress(host, it) }
            }.addNetworkInterceptor { chain ->
                checkWebFetchActive(deadline, cancel)
                checkPeer(chain, target)
                beforeSend()
                val response = chain.proceed(chain.request())
                try {
                    if (response.isSuccessful && isReadable(response)) {
                        checkWebFetchEncoding(response.headers.values("Content-Encoding"), allowGzip = true)
                    }
                    response
                } catch (failure: WebFetchFailure) {
                    response.close()
                    throw failure
                }
            }.build()

    private fun checkPeer(
        chain: Interceptor.Chain,
        target: HopTarget,
    ) {
        val peer =
            chain
                .connection()
                ?.socket()
                ?.inetAddress
                ?.address ?: throw WebFetchFailure("missing-peer")
        if (target.pinned.none { it.contentEquals(peer) }) throw WebFetchFailure("unexpected-peer")
        authorize(target.pinned, target.endpoint, target.ceiling)
        authorize(listOf(peer), target.endpoint, target.ceiling)
    }

    private fun isReadable(response: Response): Boolean {
        val media = response.body.contentType()
        return media?.type == "text" || (media?.type == "application" && media.subtype == "xhtml+xml")
    }

    private fun readPage(
        response: Response,
        url: HttpUrl,
        deadline: Long,
        cancel: CancelSignal,
        hop: Int,
    ): Hop {
        val media = response.body.contentType()
        val type = media?.let { "${it.type}/${it.subtype}" }.orEmpty()
        val redirect = response.code in REDIRECT_CODES
        val location = if (redirect) response.header("Location") else null
        if (redirect && location == null) throw WebFetchFailure("missing-redirect-location")
        val bytes =
            if (redirect || !isReadable(response) || !response.isSuccessful) {
                byteArrayOf()
            } else {
                checkWebFetchEncoding(response.headers.values("Content-Encoding"), allowGzip = false)
                readBody(response.body.source(), deadline, cancel)
            }
        return Hop(
            WebFetchPage(
                url.toString(),
                response.code,
                type,
                media?.parameter("charset"),
                bytes.copyOf(minOf(bytes.size, WEB_BODY_LIMIT)),
                bytes.size > WEB_BODY_LIMIT,
                hop,
            ),
            location,
        )
    }

    private data class HopTarget(
        val url: HttpUrl,
        val endpoint: NormalizedEndpoint,
        val pinned: List<ByteArray>,
        val ceiling: EgressPolicy,
    )

    private fun readBody(
        source: okio.BufferedSource,
        deadline: Long,
        cancel: CancelSignal,
    ): ByteArray {
        val buffer = okio.Buffer()
        while (buffer.size <= WEB_BODY_LIMIT) {
            checkWebFetchActive(deadline, cancel)
            val count = source.read(buffer, minOf(8192L, WEB_BODY_LIMIT + 1L - buffer.size))
            if (count == -1L) break
        }
        return buffer.readByteArray()
    }

    private data class Hop(
        val page: WebFetchPage,
        val location: String?,
    )

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val CANCELLATION_POLL_MS = 50L
        val cancellationWatch =
            ScheduledThreadPoolExecutor(1) { task ->
                Thread(task, "helix-web-fetch-cancel").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
    }
}

internal const val WEB_URL_LIMIT = 4096
internal const val WEB_BODY_LIMIT = 1024 * 1024
internal const val WEB_REDIRECT_LIMIT = 5
