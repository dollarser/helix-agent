package com.helix.app.mcp.oauth

import com.helix.extensions.mcp.oauth.McpOAuthTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal object OAuthCredentialCodec {
    fun encode(record: OAuthCredential): String =
        buildJsonObject {
            put("version", 1)
            put("refreshing", record.refreshing)
            put(
                "binding",
                buildJsonObject {
                    put("serverId", record.binding.serverId)
                    put("resource", record.binding.resource)
                    put("issuer", record.binding.issuer)
                    put("clientId", record.binding.clientId)
                    put("tokenEndpoint", record.binding.tokenEndpoint)
                    put("revocationEndpoint", record.binding.revocationEndpoint)
                    put("generation", record.binding.generation)
                },
            )
            put(
                "tokens",
                buildJsonObject {
                    put("accessToken", record.tokens.accessToken)
                    put("tokenType", record.tokens.tokenType)
                    put("expiresInSeconds", record.tokens.expiresInSeconds)
                    put("refreshToken", record.tokens.refreshToken)
                    put("scope", record.tokens.scope)
                    put("issuedAtMs", record.tokens.issuedAtMs)
                },
            )
        }.toString()

    fun decode(text: String): OAuthCredential {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        val binding = root.getValue("binding").jsonObject
        val tokens = root.getValue("tokens").jsonObject
        return OAuthCredential(
            binding =
                OAuthBinding(
                    serverId = binding.getValue("serverId").jsonPrimitive.content,
                    resource = binding.getValue("resource").jsonPrimitive.content,
                    issuer = binding.getValue("issuer").jsonPrimitive.content,
                    clientId = binding.getValue("clientId").jsonPrimitive.content,
                    tokenEndpoint = binding.getValue("tokenEndpoint").jsonPrimitive.content,
                    revocationEndpoint = binding.getValue("revocationEndpoint").jsonPrimitive.contentOrNull,
                    generation = binding.getValue("generation").jsonPrimitive.content,
                ),
            tokens =
                McpOAuthTokens(
                    accessToken = tokens.getValue("accessToken").jsonPrimitive.content,
                    tokenType = tokens.getValue("tokenType").jsonPrimitive.content,
                    expiresInSeconds = tokens.getValue("expiresInSeconds").jsonPrimitive.longOrNull,
                    refreshToken = tokens.getValue("refreshToken").jsonPrimitive.contentOrNull,
                    scope = tokens.getValue("scope").jsonPrimitive.contentOrNull,
                    issuedAtMs = tokens.getValue("issuedAtMs").jsonPrimitive.long,
                ),
            refreshing = root.getValue("refreshing").jsonPrimitive.boolean,
        )
    }
}
