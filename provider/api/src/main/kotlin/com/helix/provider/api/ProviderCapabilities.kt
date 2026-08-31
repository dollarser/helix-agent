package com.helix.provider.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * How a [ProviderCapabilities] snapshot was established (provider doc section 2.4,
 * HXA-025: "保存 capability snapshot 和来源。手工 override 必须标记。").
 */
public enum class CapabilitySource {
    /** Derived from the four-phase connection test (transport/auth → models → text stream → ToolCall). */
    PROBED,

    /** Manually declared by the user; the UI must mark the snapshot as "手动声明". */
    MANUAL,
}

/**
 * The capability snapshot of one provider (provider doc section 2.4, HXA-025).
 *
 * Persisted as the strict canonical JSON of [toJsonString] in the
 * `provider_configs.capability_snapshot` column (HXA-020); [parse] is the strict
 * recovery parse of that boundary (ADR-0001 discipline: any malformed stored value
 * fails closed with [IllegalArgumentException]).
 *
 * Fields the connection test does not exercise (parallel tool calls, vision,
 * reasoning, JSON-schema output, max context tokens) are conservative defaults
 * (`false`/`null`) unless a manual override says otherwise — an unproven capability
 * must never be assumed (provider doc section 2.5: Helix relies on capability
 * tests, never on product names).
 *
 * [source] marks provenance: [CapabilitySource.PROBED] snapshots come from a passed
 * connection test; [CapabilitySource.MANUAL] from a user override (marked, per the
 * doc — the value fields keep the user's declaration unchanged).
 */
public data class ProviderCapabilities(
    val streaming: Boolean,
    val toolCalls: Boolean,
    val parallelToolCalls: Boolean,
    val vision: Boolean,
    val reasoning: Boolean,
    val jsonSchemaOutput: Boolean,
    val maxContextTokens: Long?,
    val source: CapabilitySource,
) {
    init {
        require(maxContextTokens == null || maxContextTokens in 1..MAX_CONTEXT_BOUND) {
            "maxContextTokens must be null or 1..$MAX_CONTEXT_BOUND"
        }
    }

    /**
     * The manual-override mark (provider doc section 2.4): the user's declared values
     * are kept, the source is marked so the UI can show "手动声明".
     */
    public fun withManualSource(): ProviderCapabilities = copy(source = CapabilitySource.MANUAL)

    public companion object {
        const val MAX_CONTEXT_BOUND = 1_000_000L

        /**
         * Canonical wire form: fixed field order, booleans as JSON literals, the enum
         * by name, `maxContextTokens` as a number or explicit `null`.
         */
        public fun toJsonString(c: ProviderCapabilities): String =
            buildJsonObject {
                put("streaming", JsonPrimitive(c.streaming))
                put("toolCalls", JsonPrimitive(c.toolCalls))
                put("parallelToolCalls", JsonPrimitive(c.parallelToolCalls))
                put("vision", JsonPrimitive(c.vision))
                put("reasoning", JsonPrimitive(c.reasoning))
                put("jsonSchemaOutput", JsonPrimitive(c.jsonSchemaOutput))
                put("maxContextTokens", c.maxContextTokens?.let { JsonPrimitive(it) } ?: JsonNull)
                put("source", JsonPrimitive(c.source.name))
            }.toString()

        /** Strict parse of the stored column; any deviation fails closed. */
        public fun parse(raw: String): ProviderCapabilities {
            val obj: JsonObject =
                try {
                    Json.parseToJsonElement(raw).jsonObject
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("capability snapshot is not a JSON object", e)
                }
            require(obj.keys == EXPECTED_KEYS) {
                "capability snapshot keys deviate from the canonical set"
            }
            return ProviderCapabilities(
                streaming = booleanOf(obj, "streaming"),
                toolCalls = booleanOf(obj, "toolCalls"),
                parallelToolCalls = booleanOf(obj, "parallelToolCalls"),
                vision = booleanOf(obj, "vision"),
                reasoning = booleanOf(obj, "reasoning"),
                jsonSchemaOutput = booleanOf(obj, "jsonSchemaOutput"),
                maxContextTokens = maxContextTokensOf(obj),
                source = sourceOf(obj),
            )
        }

        private fun maxContextTokensOf(obj: JsonObject): Long? {
            val element = obj["maxContextTokens"]
            if (element == null || element == JsonNull) return null
            require(element !is JsonObject) { "maxContextTokens must be a number or null" }
            val primitive = element.jsonPrimitive
            // a numeric STRING is not a number: strict by design
            require(!primitive.isString) { "maxContextTokens is not a number" }
            val value = primitive.longOrNull
            require(value != null) { "maxContextTokens is not a number" }
            return value
        }

        private fun sourceOf(obj: JsonObject): CapabilitySource {
            val raw = obj["source"]?.jsonPrimitive?.content
            require(raw != null) { "source is missing" }
            val parsed = runCatching { CapabilitySource.valueOf(raw) }.getOrNull()
            require(parsed != null) { "unknown capability source: $raw" }
            return parsed
        }

        private fun booleanOf(
            obj: JsonObject,
            key: String,
        ): Boolean {
            val element: JsonElement =
                obj[key] ?: throw IllegalArgumentException("$key is missing")
            return try {
                element.jsonPrimitive.boolean
            } catch (e: IllegalStateException) {
                throw IllegalArgumentException("$key must be a boolean", e)
            }
        }

        private val EXPECTED_KEYS =
            setOf(
                "streaming",
                "toolCalls",
                "parallelToolCalls",
                "vision",
                "reasoning",
                "jsonSchemaOutput",
                "maxContextTokens",
                "source",
            )
    }
}
