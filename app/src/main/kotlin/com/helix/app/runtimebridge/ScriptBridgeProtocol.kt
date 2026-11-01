package com.helix.app.runtimebridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Closed transport envelope. Identity, mode, scopes and approval facts never come from the script. */
internal object ScriptBridgeProtocol {
    const val MAX_BYTES = 256 * 1024
    private val ids = Regex("[a-zA-Z0-9_-]{1,64}")

    data class Request(
        val id: String,
        val tool: String,
        val args: JsonObject,
    )

    fun decode(
        text: String,
        expectedProof: String?,
    ): Request {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "BRIDGE_REQUEST_TOO_LARGE" }
        val envelope = Json.parseToJsonElement(text).jsonObject
        val keys = if (expectedProof == null) setOf("id", "tool", "args") else setOf("proof", "id", "tool", "args")
        require(envelope.keys == keys) { "BRIDGE_REQUEST_INVALID" }
        if (expectedProof != null) {
            val actual = (envelope["proof"] as? JsonPrimitive)?.content.orEmpty()
            require(MessageDigest.isEqual(actual.toByteArray(), expectedProof.toByteArray())) { "BRIDGE_AUTH_REQUIRED" }
        }
        val id = envelope.getValue("id").jsonPrimitive.content
        require(envelope.getValue("id").jsonPrimitive.isString)
        require(ids.matches(id)) { "BRIDGE_REQUEST_ID_INVALID" }
        val tool = envelope.getValue("tool").jsonPrimitive.content
        require(envelope.getValue("tool").jsonPrimitive.isString)
        require(tool.length in 1..128) { "BRIDGE_TOOL_NAME_INVALID" }
        val args = envelope.getValue("args").jsonObject
        require(expectedProof == null || !args.toString().contains(expectedProof)) { "BRIDGE_CREDENTIAL_IN_ARGUMENTS" }
        return Request(id, tool, args)
    }

    fun failure(code: String): String =
        buildJsonObject {
            put("status", "REFUSED")
            put("code", code)
            put("sideEffectFree", true)
        }.toString()
}
