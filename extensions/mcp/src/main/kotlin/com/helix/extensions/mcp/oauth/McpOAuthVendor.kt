package com.helix.extensions.mcp.oauth

import com.helix.core.model.NormalizedEndpoint

enum class McpOAuthVendor { SLACK, GITHUB }

fun mcpOAuthVendor(url: String): McpOAuthVendor? {
    val endpoint = NormalizedEndpoint.parse(url)
    if (endpoint.scheme != "https" || endpoint.port != 443) return null
    return when (endpoint.host) {
        "mcp.slack.com" -> McpOAuthVendor.SLACK
        "api.githubcopilot.com" -> McpOAuthVendor.GITHUB
        else -> null
    }
}
