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
                addNetworkInterceptor(McpInitializeResponseGuard)
                addNetworkInterceptor(McpOkHttpResponseLimit)
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
