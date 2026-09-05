package com.helix.runtime.cli.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

data class CliRuntimeStatus(
    val protocolVersion: Int,
    val runtimeVersion: String,
    val abi: String,
    val lockSha256: String,
    val agentBackendState: String,
)

object CliRuntimeStatusCodec {
    private val sha256 = Regex("[0-9a-f]{64}")

    fun decode(document: String): CliRuntimeStatus {
        require(document.encodeToByteArray().size <= CliRuntimeProtocol.MAX_STATUS_BYTES)
        val value = Json.parseToJsonElement(document).jsonObject
        val required = setOf("protocolVersion", "runtimeVersion", "abi", "lockSha256", "agentBackendState")
        require(value.keys.containsAll(required))
        val status =
            CliRuntimeStatus(
                protocolVersion = value.getValue("protocolVersion").jsonPrimitive.int,
                runtimeVersion = value.getValue("runtimeVersion").jsonPrimitive.content,
                abi = value.getValue("abi").jsonPrimitive.content,
                lockSha256 = value.getValue("lockSha256").jsonPrimitive.content,
                agentBackendState = value.getValue("agentBackendState").jsonPrimitive.content,
            )
        require(status.protocolVersion == CliRuntimeProtocol.VERSION)
        require(status.runtimeVersion.length in 1..64)
        require(status.abi == "arm64-v8a")
        require(sha256.matches(status.lockSha256))
        require(status.agentBackendState == "NOT_REGISTERED")
        return status
    }
}
