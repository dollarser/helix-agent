package com.helix.extensions.mcp.oauth

import kotlinx.serialization.Serializable

@Serializable
data class McpDeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

sealed interface McpDevicePollResult {
    data class Success(
        val tokens: McpOAuthTokens,
    ) : McpDevicePollResult

    data object Pending : McpDevicePollResult

    data object SlowDown : McpDevicePollResult

    data class Error(
        val message: String,
        val errorCode: String? = null,
    ) : McpDevicePollResult
}
