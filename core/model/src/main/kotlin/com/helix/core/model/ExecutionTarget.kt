package com.helix.core.model

import com.helix.core.model.internal.Json
import com.helix.core.model.internal.parseJson
import com.helix.core.model.internal.requireInt
import com.helix.core.model.internal.requireObject
import com.helix.core.model.internal.requireString
import com.helix.core.model.internal.requireStringObject

/**
 * Execution target kinds (modes doc section 8). The baseline is single-device: only local
 * targets exist. Remote workers / cloud sandboxes / desktop pairing would add new values plus a
 * new transport in a later milestone; nothing in this module assumes network delivery.
 *
 * PRoot and CLI targets are separate, separately signed Runtime applications (own UIDs), not
 * secondary processes of the main app; they are reached over signature-protected Binder/PFD IPC
 * and still count as local executors.
 */
enum class ExecutionTargetType {
    /** In-app Android tools: browser, files, notifications, calendar, clipboard. */
    LOCAL_ANDROID,

    /** Zipline/QuickJS executor in a non-exported `isolatedProcess` service. */
    LOCAL_QUICKJS,

    /** PRoot + Alpine Runtime APK with its own UID. */
    LOCAL_PROOT,

    /** Official CLI subscription backend Runtime APK with its own UID. */
    LOCAL_CLI_RUNTIME,
}

/**
 * Descriptor of one available execution target, persisted in the `execution_targets` table and
 * bound into approval hashing (`executionTargetId`).
 *
 * [attributes] carries bounded, opaque, identifier-style metadata such as a RootFS manifest
 * hash or a CLI version; values must not contain secrets, paths or unbounded content.
 *
 * Storage encoding: fixed-order canonical JSON, `attributes` sorted by key (see ADR-0001).
 */
data class ExecutionTargetDescriptor(
    val id: ExecutionTargetId,
    val type: ExecutionTargetType,
    val protocolVersion: Int,
    val displayName: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(protocolVersion >= MIN_PROTOCOL_VERSION) {
            "protocolVersion must be >= $MIN_PROTOCOL_VERSION"
        }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName exceeds $MAX_DISPLAY_NAME_LENGTH characters"
        }
        require(attributes.size <= MAX_ATTRIBUTES) {
            "attributes may hold at most $MAX_ATTRIBUTES entries"
        }
        attributes.forEach { (key, value) ->
            require(key.length in 1..MAX_ATTRIBUTE_KEY_LENGTH) {
                "attribute key length must be 1..$MAX_ATTRIBUTE_KEY_LENGTH"
            }
            key.forEach { c ->
                require(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') {
                    "attribute key contains invalid character"
                }
            }
            require(value.length <= MAX_ATTRIBUTE_VALUE_LENGTH) {
                "attribute value exceeds $MAX_ATTRIBUTE_VALUE_LENGTH characters"
            }
            value.forEach { c -> require(c.code >= 0x20) { "attribute value contains a control character" } }
        }
    }

    fun toStorageString(): String {
        val attrs =
            Json.objectFromSortedEntries(
                attributes.entries.map { (k, v) -> k to Json.string(v) },
            )
        val pairs =
            listOf(
                "id" to Json.string(id.value),
                "type" to Json.string(type.name),
                "protocolVersion" to Json.long(protocolVersion.toLong()),
                "displayName" to Json.string(displayName),
                "attributes" to attrs,
            )
        return Json.objectBody(pairs)
    }

    companion object {
        const val MIN_PROTOCOL_VERSION = 1
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val MAX_ATTRIBUTES = 16
        const val MAX_ATTRIBUTE_KEY_LENGTH = 64
        const val MAX_ATTRIBUTE_VALUE_LENGTH = 512

        private val FIELDS = listOf("id", "type", "protocolVersion", "displayName", "attributes")

        private val TYPES_BY_NAME: Map<String, ExecutionTargetType> =
            ExecutionTargetType.entries.associateBy { it.name }

        fun parse(text: String): ExecutionTargetDescriptor {
            val fields = parseJson(text).requireObject("ExecutionTargetDescriptor", FIELDS)
            val typeName = fields.requireString("type")
            val type = TYPES_BY_NAME[typeName] ?: throw IllegalArgumentException("unknown execution target type")
            return ExecutionTargetDescriptor(
                id = ExecutionTargetId(fields.requireString("id")),
                type = type,
                protocolVersion = fields.requireInt("protocolVersion"),
                displayName = fields.requireString("displayName"),
                attributes = fields.requireStringObject("attributes"),
            )
        }
    }
}
