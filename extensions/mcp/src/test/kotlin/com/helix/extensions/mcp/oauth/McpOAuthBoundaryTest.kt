package com.helix.extensions.mcp.oauth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class McpOAuthBoundaryTest {
    @Test fun vendorMatchingUsesExactHttpsResourceHost() {
        assertEquals(McpOAuthVendor.SLACK, mcpOAuthVendor("https://mcp.slack.com/mcp"))
        assertEquals(McpOAuthVendor.GITHUB, mcpOAuthVendor("https://api.githubcopilot.com/mcp/"))
        listOf(
            "https://slack.com.evil.example/mcp",
            "https://evil.example/slack.com",
            "https://github.com.evil.example/",
            "http://mcp.slack.com/mcp",
            "https://mcp.slack.com:8443/mcp",
        ).forEach { assertNull(mcpOAuthVendor(it)) }
    }

    @Test fun responseLimitCountsBytesAndBoundsUnknownLength() {
        assertEquals("abcd", "abcd".toResponseBody().oauthText(4))
        assertThrows(IllegalArgumentException::class.java) { "abcde".toResponseBody().oauthText(4) }
        val body =
            object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()

                override fun contentLength() = -1L

                override fun source(): BufferedSource = Buffer().writeUtf8("abcde")
            }
        assertThrows(IllegalArgumentException::class.java) { body.oauthText(4) }
        assertThrows(IllegalArgumentException::class.java) { "\u00e9\u00e9\u00e9".toResponseBody().oauthText(4) }
    }
}
