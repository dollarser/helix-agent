package com.helix.extensions.mcp

import com.helix.core.model.NormalizedEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import okhttp3.Dns
import java.net.Proxy
import java.net.UnknownHostException

internal fun newOkHttpClient(
    authenticatedEndpoint: NormalizedEndpoint?,
    bearerToken: String?,
    networkPermit: McpNetworkPermit?,
): HttpClient =
    HttpClient(OkHttp) {
        install(SSE)
        engine {
            // Redirects need the same per-hop origin/DNS/peer checks as HXA-066. The SDK
            // transport does not expose that decision point, so reject rather than risk
            // forwarding a bearer credential to a different origin.
            config {
                dns {
                    com.helix.core.policy.network.NativeNetwork
                        .resolve(it)
                }
                // Application interceptors see OkHttp's decoded response. Network interceptors see gzip bytes.
                addInterceptor(McpInitializeResponseGuard)
                addInterceptor(McpOkHttpResponseLimit)
                retryOnConnectionFailure(false)
                addInterceptor(McpSingleUseRequests)
                followRedirects(false)
                followSslRedirects(false)
                if (networkPermit != null) {
                    proxy(Proxy.NO_PROXY)
                    dns(
                        Dns { hostname ->
                            if (hostname != networkPermit.host) {
                                throw UnknownHostException("MCP transport refused an unexpected host")
                            }
                            networkPermit.pinnedAddresses(hostname)
                        },
                    )
                }
            }
        }
        if (authenticatedEndpoint != null && bearerToken != null) {
            engine {
                addInterceptor { chain ->
                    val request = chain.request()
                    val requestUrl = request.url
                    val sameOrigin =
                        requestUrl.scheme == authenticatedEndpoint.scheme &&
                            requestUrl.host == authenticatedEndpoint.host &&
                            requestUrl.port == authenticatedEndpoint.port
                    val authorizedRequest =
                        if (sameOrigin) {
                            request
                                .newBuilder()
                                .header("Authorization", "Bearer $bearerToken")
                                .build()
                        } else {
                            request.newBuilder().removeHeader("Authorization").build()
                        }
                    chain.proceed(authorizedRequest)
                }
            }
        }
    }

/** An accepted tool POST must not be followed by an implicit 503/redirect or connection replay. */
private class McpSingleUseBody(
    private val delegate: okhttp3.RequestBody,
) : okhttp3.RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun isOneShot() = true

    override fun isDuplex() = delegate.isDuplex()

    override fun writeTo(sink: okio.BufferedSink) = delegate.writeTo(sink)
}

private object McpSingleUseRequests : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val body = request.body
        val singleUse =
            if (body == null) {
                request
            } else {
                request
                    .newBuilder()
                    .method(request.method, McpSingleUseBody(body))
                    .build()
            }
        return chain.proceed(singleUse)
    }
}
