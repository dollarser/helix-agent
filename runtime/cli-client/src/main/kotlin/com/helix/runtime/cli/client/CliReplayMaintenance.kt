package com.helix.runtime.cli.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Opaque local bookkeeping only; never contains provider signatures, arguments or credentials. */
data class CliReplayEntry(
    val key: String,
    val fingerprint: String?,
    val owner: String?,
    val bytes: Long,
) {
    init {
        CliReplayMaintenance.requireHash(key)
        fingerprint?.let(CliReplayMaintenance::requireHash)
        owner?.let(CliReplayMaintenance::requireOwner)
        require(bytes in 0..Long.MAX_VALUE / CliReplayMaintenance.PAGE_SIZE)
    }
}

data class CliReplayPage(
    val entries: List<CliReplayEntry>,
    val nextAfter: String?,
) {
    init {
        require(entries.size <= CliReplayMaintenance.PAGE_SIZE)
        require(entries.map { it.key }.distinct().size == entries.size)
        require(entries.zipWithNext().all { (a, b) -> a.key < b.key })
        nextAfter?.let { require(it == entries.lastOrNull()?.key) }
    }
}

data class CliReplayPruneResult(
    val deleted: Int,
    val retained: Int,
    val failed: Int,
    val deletedBytes: Long,
    val busy: Boolean = false,
)

/** Shared bounded wire contract for explicit user-driven maintenance. */
object CliReplayMaintenance {
    const val PAGE_SIZE = 32
    const val MAX_WIRE_CHARS = 16 * 1024
    const val EPHEMERAL = "ephemeral"
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun requireHash(value: String) = require(hashPattern.matches(value)) { "Invalid replay identity" }

    fun requireOwner(value: String) =
        require(value == EPHEMERAL || hashPattern.matches(value)) { "Invalid replay owner" }

    fun hash(value: String): String = hash(value.toByteArray(Charsets.UTF_8))

    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun encode(page: CliReplayPage): String =
        buildJsonObject {
            put(
                "entries",
                JsonArray(
                    page.entries.map { entry ->
                        buildJsonObject {
                            put("key", entry.key)
                            put("fingerprint", entry.fingerprint?.let(::JsonPrimitive) ?: JsonNull)
                            put("owner", entry.owner?.let(::JsonPrimitive) ?: JsonNull)
                            put("bytes", entry.bytes)
                        }
                    },
                ),
            )
            put("nextAfter", page.nextAfter?.let(::JsonPrimitive) ?: JsonNull)
        }.toString().also { require(it.length <= MAX_WIRE_CHARS) }

    fun decode(text: String): CliReplayPage {
        require(text.length <= MAX_WIRE_CHARS)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("entries", "nextAfter"))
        val values = root.getValue("entries").jsonArray
        require(values.size <= PAGE_SIZE)
        val entries =
            values.map { value ->
                val entry = value.jsonObject
                require(entry.keys == setOf("key", "fingerprint", "owner", "bytes"))
                val byteCount = entry.getValue("bytes").jsonPrimitive
                require(!byteCount.isString)
                CliReplayEntry(
                    requireNotNull(optionalString(entry.getValue("key"))),
                    optionalString(entry.getValue("fingerprint")),
                    optionalString(entry.getValue("owner")),
                    byteCount.long,
                )
            }
        return CliReplayPage(entries, optionalString(root.getValue("nextAfter")))
    }

    private fun optionalString(value: kotlinx.serialization.json.JsonElement): String? {
        if (value == JsonNull) return null
        val primitive = value.jsonPrimitive
        require(primitive.isString)
        return primitive.content
    }
}
