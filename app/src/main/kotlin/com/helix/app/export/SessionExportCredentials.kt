package com.helix.app.export

import com.helix.app.chat.ForbiddenContentGuard
import com.helix.core.storage.export.SessionExportSanitizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Field exclusion plus existing free-text credential patterns; this is not an anonymity guarantee. */
internal object SessionExportCredentials : SessionExportSanitizer {
    override fun sanitize(
        text: String,
        structured: Boolean,
    ): String {
        val candidate = text.trimStart()
        val looksStructured = candidate.startsWith('{') || candidate.startsWith('[')
        return if (structured ||
            looksStructured
        ) {
            sanitizeJson(text, structured)
        } else {
            ForbiddenContentGuard.redactKnownCredentials(text)
        }
    }

    private fun sanitizeJson(
        text: String,
        required: Boolean,
    ): String {
        if (!boundedDepth(text)) return "[redacted: JSON nesting exceeds export limit]"
        val parsed =
            try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        val sanitized = parsed?.let(::visit)
        return when {
            sanitized == null -> {
                if (required) {
                    "[redacted: structured content could not be checked]"
                } else {
                    ForbiddenContentGuard.redactKnownCredentials(text)
                }
            }

            sanitized == parsed -> {
                text
            }

            else -> {
                sanitized.toString()
            }
        }
    }

    private fun visit(value: JsonElement): JsonElement =
        when (value) {
            is JsonObject -> {
                JsonObject(
                    value.mapValues { (key, element) ->
                        if (key.lowercase().replace("_", "").replace("-", "") in CREDENTIAL_FIELDS) {
                            JsonPrimitive("[redacted: credential field]")
                        } else {
                            visit(element)
                        }
                    },
                )
            }

            is JsonArray -> {
                JsonArray(value.map(::visit))
            }

            is JsonPrimitive -> {
                if (value.isString) {
                    JsonPrimitive(ForbiddenContentGuard.redactKnownCredentials(value.content))
                } else {
                    value
                }
            }
        }

    private fun boundedDepth(text: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            when {
                escaped -> escaped = false
                quoted && char == '\\' -> escaped = true
                char == '"' -> quoted = !quoted
                !quoted && (char == '{' || char == '[') -> if (++depth > MAX_DEPTH) return false
                !quoted && (char == '}' || char == ']') -> depth--
            }
        }
        return true
    }

    private const val MAX_DEPTH = 64
    private val CREDENTIAL_FIELDS =
        setOf(
            "authorization",
            "proxyauthorization",
            "cookie",
            "setcookie",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "apikey",
            "password",
            "clientsecret",
            "secret",
            "token",
            "approvalproof",
            "bindinghash",
        )
}
