package com.helix.app.mcp.oauth

import java.net.URI
import java.net.URLDecoder

internal class McpOAuthCallback private constructor(
    val uri: URI,
    val parameters: Map<String, String>,
) {
    fun matches(attempt: McpOAuthAttempt): Boolean {
        val redirect = URI(attempt.redirectUri)
        return uri.scheme == redirect.scheme && uri.rawAuthority == redirect.rawAuthority &&
            uri.rawPath == redirect.rawPath && uri.rawFragment == null &&
            (parameters["iss"] == null || parameters["iss"] == attempt.issuer)
    }

    companion object {
        fun parse(value: String): McpOAuthCallback {
            require(value.length <= 8192)
            val uri = URI(value)
            require(uri.rawFragment == null && uri.rawUserInfo == null)
            val pairs =
                uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { pair ->
                    val parts = pair.split('=', limit = 2)
                    require(parts.size == 2)
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
            require(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate OAuth parameters" }
            return McpOAuthCallback(uri, pairs.toMap())
        }
    }
}
