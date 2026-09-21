package com.helix.app.mcp.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

data class McpOAuthAttempt(
    val attemptId: String,
    val serverId: String,
    val issuer: String,
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val state: String,
    val codeVerifier: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAtMs

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L // 10 minutes
    }
}

object McpOAuthAttemptCodec {
    fun encode(attempt: McpOAuthAttempt): String =
        buildJsonObject {
            put("attemptId", JsonPrimitive(attempt.attemptId))
            put("serverId", JsonPrimitive(attempt.serverId))
            put("issuer", JsonPrimitive(attempt.issuer))
            put("tokenEndpoint", JsonPrimitive(attempt.tokenEndpoint))
            put("clientId", JsonPrimitive(attempt.clientId))
            put("redirectUri", JsonPrimitive(attempt.redirectUri))
            put("scope", JsonPrimitive(attempt.scope))
            put("state", JsonPrimitive(attempt.state))
            put("codeVerifier", JsonPrimitive(attempt.codeVerifier))
            put("createdAtMs", JsonPrimitive(attempt.createdAtMs))
            put("expiresAtMs", JsonPrimitive(attempt.expiresAtMs))
        }.toString()

    fun decode(jsonStr: String): McpOAuthAttempt {
        val root = Json.parseToJsonElement(jsonStr).jsonObject
        return McpOAuthAttempt(
            attemptId = root.getValue("attemptId").jsonPrimitive.content,
            serverId = root.getValue("serverId").jsonPrimitive.content,
            issuer = root.getValue("issuer").jsonPrimitive.content,
            tokenEndpoint = root.getValue("tokenEndpoint").jsonPrimitive.content,
            clientId = root.getValue("clientId").jsonPrimitive.content,
            redirectUri = root.getValue("redirectUri").jsonPrimitive.content,
            scope = root.getValue("scope").jsonPrimitive.content,
            state = root.getValue("state").jsonPrimitive.content,
            codeVerifier = root.getValue("codeVerifier").jsonPrimitive.content,
            createdAtMs = root.getValue("createdAtMs").jsonPrimitive.long,
            expiresAtMs = root.getValue("expiresAtMs").jsonPrimitive.long,
        )
    }
}

class McpOAuthAttemptStore(
    private val directory: File,
) {
    init {
        directory.mkdirs()
    }

    /**
     * Saves an attempt atomically to [directory] named `<state>.json`.
     */
    fun saveAttempt(attempt: McpOAuthAttempt) {
        val file = File(directory, "${attempt.state}.json")
        val tempFile = File(directory, "${attempt.state}.json.tmp")
        val content = McpOAuthAttemptCodec.encode(attempt)
        tempFile.writeText(content)
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
    }

    /**
     * Consumes an attempt by [state] (One-Time-Consumption per ADR-CONNECTORS-002).
     * The file is immediately deleted to prevent replay attacks.
     * Returns null if not found or expired.
     */
    fun consumeAttempt(
        state: String,
        nowMs: Long = System.currentTimeMillis(),
    ): McpOAuthAttempt? {
        val file = File(directory, "$state.json")
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            file.delete()
            val attempt = McpOAuthAttemptCodec.decode(content)
            if (attempt.isExpired(nowMs)) null else attempt
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun cancelAttempt(state: String) {
        File(directory, "$state.json").delete()
    }

    fun cleanupExpired(nowMs: Long = System.currentTimeMillis()) {
        directory.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { file ->
            cleanIfExpired(file, nowMs)
        }
    }

    private fun cleanIfExpired(
        file: File,
        nowMs: Long,
    ) {
        try {
            val content = file.readText()
            val attempt = McpOAuthAttemptCodec.decode(content)
            if (attempt.isExpired(nowMs)) {
                file.delete()
            }
        } catch (_: Exception) {
            file.delete()
        }
    }
}
