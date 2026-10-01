package com.helix.app.mcp.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

object McpOAuthAttemptCodec {
    fun encode(attempt: McpOAuthAttempt): String =
        buildJsonObject {
            put("version", 2)
            put("attemptId", attempt.attemptId)
            put("serverId", attempt.serverId)
            put("issuer", attempt.issuer)
            put("tokenEndpoint", attempt.tokenEndpoint)
            put("clientId", attempt.clientId)
            put("redirectUri", attempt.redirectUri)
            put("scope", attempt.scope)
            put("state", attempt.state)
            put("createdAtMs", attempt.createdAtMs)
            put("expiresAtMs", attempt.expiresAtMs)
            put("resource", attempt.resource)
            put("revocationEndpoint", attempt.revocationEndpoint)
            put("issuerParameterRequired", attempt.issuerParameterRequired)
        }.toString()

    fun decode(jsonStr: String): McpOAuthAttempt {
        val root = Json.parseToJsonElement(jsonStr).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 2 && "codeVerifier" !in root)
        val issuerRequired = root.getValue("issuerParameterRequired").jsonPrimitive
        require(!issuerRequired.isString)
        return McpOAuthAttempt(
            attemptId = root.getValue("attemptId").jsonPrimitive.content,
            serverId = root.getValue("serverId").jsonPrimitive.content,
            issuer = root.getValue("issuer").jsonPrimitive.content,
            tokenEndpoint = root.getValue("tokenEndpoint").jsonPrimitive.content,
            clientId = root.getValue("clientId").jsonPrimitive.content,
            redirectUri = root.getValue("redirectUri").jsonPrimitive.content,
            scope = root.getValue("scope").jsonPrimitive.content,
            state = root.getValue("state").jsonPrimitive.content,
            createdAtMs = root.getValue("createdAtMs").jsonPrimitive.long,
            expiresAtMs = root.getValue("expiresAtMs").jsonPrimitive.long,
            resource = root.getValue("resource").jsonPrimitive.content,
            revocationEndpoint = root.getValue("revocationEndpoint").jsonPrimitive.contentOrNull,
            issuerParameterRequired = requireNotNull(issuerRequired.content.toBooleanStrictOrNull()),
        )
    }
}
