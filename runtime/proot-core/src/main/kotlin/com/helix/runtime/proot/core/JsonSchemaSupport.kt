package com.helix.runtime.proot.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URISyntaxException

/**
 * Strict JsonElement parsing helpers shared by the HXA-080 codecs. Every accessor is
 * fail-closed: a missing key, a wrong JSON type or a malformed value throws
 * [RuntimeLockSchemaException] with the field path in the message. No accessor ever
 * returns a default.
 */
@Suppress("TooManyFunctions") // one helper per schema primitive; splitting fragments the tool
internal object JsonSchemaSupport {
    private val json = Json { isLenient = false }

    val SHA256_PATTERN: Regex by lazy { Regex("[0-9a-f]{64}") }

    /** Upper bound for any locked artifact size (1 TiB): a realistic runtime asset is
     * O(100 MiB); a larger value is a corrupt or malicious document, not a big asset. */
    const val MAX_ARTIFACT_SIZE_BYTES: Long = 1024L * 1024L * 1024L * 1024L

    fun parseDocument(
        text: String,
        what: String,
    ): JsonObject {
        val element: JsonElement =
            try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw RuntimeLockSchemaException("$what is not valid JSON: ${e.message}", e)
            }
        val obj =
            element as? JsonObject
                ?: throw RuntimeLockSchemaException("$what must be a JSON object")
        return obj
    }

    /** Checks [obj] has EXACTLY [expected] keys (unknown keys are a schema violation). */
    fun checkKeys(
        obj: JsonObject,
        expected: Set<String>,
        what: String,
    ) {
        val actual = obj.keys
        actual.filter { it !in expected }.forEach { key ->
            throw RuntimeLockSchemaException("$what has unknown key: $key")
        }
        expected.filter { it !in actual }.forEach { key ->
            throw RuntimeLockSchemaException("$what is missing required key: $key")
        }
    }

    fun string(
        obj: JsonObject,
        key: String,
        what: String,
        requireNonBlank: Boolean = true,
    ): String {
        val element =
            obj[key]
                ?: throw RuntimeLockSchemaException("$what is missing required key: $key")
        val primitive =
            (element as? JsonPrimitive)?.takeIf { it.isString }
                ?: throw RuntimeLockSchemaException("$what.$key must be a string")
        return validateText(primitive.content, what, key, requireNonBlank)
    }

    private fun validateText(
        value: String,
        what: String,
        key: String,
        requireNonBlank: Boolean,
    ): String {
        if (requireNonBlank && value.isBlank()) {
            throw RuntimeLockSchemaException("$what.$key must not be blank")
        }
        if (value.any { it.isISOControl() }) {
            throw RuntimeLockSchemaException("$what.$key must not contain control characters")
        }
        return value
    }

    fun long(
        obj: JsonObject,
        key: String,
        what: String,
        minimum: Long,
        maximum: Long,
    ): Long {
        val element =
            obj[key]
                ?: throw RuntimeLockSchemaException("$what is missing required key: $key")
        val value =
            (element as? JsonPrimitive)?.longOrNull
                ?: throw RuntimeLockSchemaException("$what.$key must be an integer")
        return validateRange(value, minimum, maximum, what, key)
    }

    private fun validateRange(
        value: Long,
        minimum: Long,
        maximum: Long,
        what: String,
        key: String,
    ): Long {
        if (value < minimum || value > maximum) {
            throw RuntimeLockSchemaException("$what.$key must be within [$minimum, $maximum]")
        }
        return value
    }

    fun int(
        obj: JsonObject,
        key: String,
        what: String,
    ): Int {
        val value = long(obj, key, what, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        return value.toInt()
    }

    fun array(
        obj: JsonObject,
        key: String,
        what: String,
    ): JsonArray {
        val element =
            obj[key]
                ?: throw RuntimeLockSchemaException("$what is missing required key: $key")
        return element as? JsonArray
            ?: throw RuntimeLockSchemaException("$what.$key must be an array")
    }

    fun objectValue(
        obj: JsonObject,
        key: String,
        what: String,
    ): JsonObject {
        val element =
            obj[key]
                ?: throw RuntimeLockSchemaException("$what is missing required key: $key")
        return element as? JsonObject
            ?: throw RuntimeLockSchemaException("$what.$key must be an object")
    }

    /**
     * Canonical SHA-256 form: 64 lowercase hex chars. Uppercase input is rejected (the
     * canonical on-disk form is lowercase; accepting both would let a tampered document
     * flip case to dodge a string comparison).
     */
    fun sha256(
        value: String,
        what: String,
        key: String,
    ): String {
        if (!SHA256_PATTERN.matches(value)) {
            throw RuntimeLockSchemaException(
                "$what.$key must be a 64-char lowercase hex SHA-256, got: $value",
            )
        }
        return value
    }

    /**
     * Build-time asset URL: HTTPS only (the device never downloads executables — the URL is
     * the CI fetch origin, architecture doc section 6.4), no userinfo, no fragment, and a
     * real hostname (no IP literals, no localhost): a lock pointing at a local or
     * credential-bearing origin is not a build-time truth.
     */
    fun assetUrl(
        value: String,
        what: String,
        key: String,
    ): String {
        requireHttps(value, what, key)
        val uri = parseUri(value, what, key)
        rejectUriExtras(uri, what, key)
        requireRealHost(uri.host.orEmpty(), what, key)
        return value
    }

    private fun requireHttps(
        value: String,
        what: String,
        key: String,
    ) {
        if (!value.startsWith("https://")) {
            throw RuntimeLockSchemaException("$what.$key must be an https URL")
        }
    }

    private fun parseUri(
        value: String,
        what: String,
        key: String,
    ): URI =
        try {
            URI(value)
        } catch (e: URISyntaxException) {
            throw RuntimeLockSchemaException("$what.$key is not a valid URL", e)
        }

    private fun rejectUriExtras(
        uri: URI,
        what: String,
        key: String,
    ) {
        if (uri.userInfo != null) {
            throw RuntimeLockSchemaException("$what.$key must not contain credentials")
        }
        if (uri.fragment != null) {
            throw RuntimeLockSchemaException("$what.$key must not contain a fragment")
        }
    }

    private fun requireRealHost(
        host: String,
        what: String,
        key: String,
    ) {
        if (host.isEmpty() || isIpLiteral(host)) {
            throw RuntimeLockSchemaException("$what.$key must name a real host")
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.split('.').all { it.isNotEmpty() && it.all(Char::isDigit) }

    /**
     * A relative reference into the Runtime APK's embedded directories (`licenses/`,
     * `patches/`): no absolute paths, no `..` segments, no backslashes, no empty segments.
     */
    fun embeddedRef(
        value: String,
        what: String,
        key: String,
    ): String {
        if (value.isEmpty() || value.startsWith('/') || value.contains('\\')) {
            throw RuntimeLockSchemaException("$what.$key must be a relative forward-slash path")
        }
        value.split('/').forEach { segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw RuntimeLockSchemaException("$what.$key must not contain . or .. segments")
            }
        }
        return value
    }
}
