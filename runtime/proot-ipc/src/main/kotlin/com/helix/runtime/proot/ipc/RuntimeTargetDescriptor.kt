package com.helix.runtime.proot.ipc

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The bounded execution-target descriptor the companion reports at handshake
 * (ADR-0007 decision 2). It is the ONLY cross-APK claim about what the Runtime is;
 * process liveness is never part of it, and the main app persists the first
 * verified copy as the anchor for later re-handshakes.
 *
 * [lockSha256] is the CANONICAL lock fingerprint (parse -> encode -> SHA-256, the
 * HXA-080 `RuntimeLockCodec.sha256Hex` definition) — not the raw file hash — so both
 * sides compare the version truth, not a serialization accident.
 */
data class RuntimeTargetDescriptor(
    val protocolVersion: Int,
    val runtimeVersion: String,
    val abi: String,
    val lockSha256: String,
    val capabilities: List<String>,
) {
    init {
        require(protocolVersion in 1..Int.MAX_VALUE) { "protocolVersion out of range: $protocolVersion" }
        require(runtimeVersion.isNotBlank() && runtimeVersion.length <= 64) { "runtimeVersion invalid" }
        require(abi in RuntimeTargetDescriptorCodec.ABIS) { "abi not in closed set: $abi" }
        require(
            lockSha256.length == 64 && lockSha256.all { it in '0'..'9' || it in 'a'..'f' },
        ) { "lockSha256 is not canonical lowercase hex" }
        require(capabilities.isNotEmpty() && capabilities.size == capabilities.toSet().size) {
            "capabilities empty or duplicated"
        }
        require(capabilities.all { it in RuntimeTargetDescriptorCodec.CAPABILITIES }) {
            "unknown capability: ${capabilities.first { it !in RuntimeTargetDescriptorCodec.CAPABILITIES }}"
        }
        require("handshake" in capabilities) { "handshake capability missing" }
    }
}

/**
 * Strict fail-closed codec for [RuntimeTargetDescriptor], in the house style of the
 * `:runtime:proot-core` codecs (JsonElement API, no serialization compiler plugin):
 * exact key set, typed accessors, closed-set domain validation, canonical key order
 * in [encode].
 */
object RuntimeTargetDescriptorCodec {
    /** Closed ABI set of the PRoot Runtime (schema supports both; assets ship arm64-v8a). */
    val ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

    /** Closed capability set; "stdio" (HXA-073) is stdin-backed bounded PRoot jobs. */
    val CAPABILITIES: Set<String> = setOf("handshake", "jobs", "stdio")

    private const val WHAT = "runtime target descriptor"

    private val KEYS = setOf("protocolVersion", "runtimeVersion", "abi", "lockSha256", "capabilities")

    private val json = Json { isLenient = false }

    /** Parses and domain-validates one descriptor document. Throws on ANY violation. */
    @Suppress("ThrowsCount") // one throw per distinct schema violation

    fun parse(document: String): RuntimeTargetDescriptor {
        val element: JsonElement =
            try {
                json.parseToJsonElement(document)
            } catch (e: SerializationException) {
                throw ProotIpcException("$WHAT is not valid JSON: ${e.message?.take(120)}", e)
            }
        val obj = element as? JsonObject ?: throw ProotIpcException("$WHAT must be a JSON object")
        checkKeys(obj)
        val protocolVersion = int(obj, "protocolVersion")
        val runtimeVersion = string(obj, "runtimeVersion")
        val abi = string(obj, "abi")
        val lockSha256 = string(obj, "lockSha256")
        val capabilities =
            (obj["capabilities"] as? JsonArray)
                ?.map {
                    (it as? JsonPrimitive)?.content
                        ?: throw ProotIpcException("$WHAT.capabilities[] must be a string")
                }
                ?: throw ProotIpcException("$WHAT.capabilities must be an array")
        // The constructor re-runs the full domain validation (closed sets, hex format).
        return RuntimeTargetDescriptor(protocolVersion, runtimeVersion, abi, lockSha256, capabilities)
    }

    /** Canonical encoding (fixed key order); the input must already be valid. */
    fun encode(descriptor: RuntimeTargetDescriptor): String {
        val obj =
            buildJsonObject {
                put("protocolVersion", descriptor.protocolVersion)
                put("runtimeVersion", descriptor.runtimeVersion)
                put("abi", descriptor.abi)
                put("lockSha256", descriptor.lockSha256)
                put(
                    "capabilities",
                    buildJsonArray {
                        descriptor.capabilities.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** Canonical fingerprint (SHA-256 of the canonical encoding). */
    fun sha256Hex(descriptor: RuntimeTargetDescriptor): String {
        val bytes = encode(descriptor).encodeToByteArray()
        return MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    private fun checkKeys(obj: JsonObject) {
        obj.keys.filter { it !in KEYS }.forEach { key ->
            throw ProotIpcException("$WHAT has unknown key: $key")
        }
        KEYS.filter { it !in obj.keys }.forEach { key ->
            throw ProotIpcException("$WHAT is missing required key: $key")
        }
    }

    private fun Long.toIntExactOrNull(): Int? =
        if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null

    private fun string(
        obj: JsonObject,
        key: String,
    ): String {
        val primitive =
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }
                ?: throw ProotIpcException("$WHAT.$key must be a string")
        return primitive.content
    }

    private fun int(
        obj: JsonObject,
        key: String,
    ): Int {
        val value =
            (obj[key] as? JsonPrimitive)?.longOrNull
                ?: throw ProotIpcException("$WHAT.$key must be an integer")
        return value.toIntExactOrNull() ?: throw ProotIpcException("$WHAT.$key overflows int")
    }
}

/** Stable protocol-level failure; never carries paths or raw peer bytes. */
class ProotIpcException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
