package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The JSON Schema SUBSET accepted by the tool framework (HXA-031).
 *
 * Allowed types: `object`, `array`, `string`, `number`, `integer`, `boolean`.
 * Allowed keywords by type (plus the type-agnostic `type`/`enum` and the
 * standard annotation keywords, which are ALLOWED AND IGNORED — schemas carry
 * per-property `description`s for the model table):
 *
 * | type      | keywords                                                                 |
 * | --------- | ------------------------------------------------------------------------ |
 * | object    | `properties`, `required`, `additionalProperties`                         |
 * | array     | `items`, `minItems`, `maxItems`                                          |
 * | string    | `minLength`, `maxLength`, `pattern`                                      |
 * | number    | `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`             |
 * | integer   | same as `number`                                                         |
 * | boolean   | (none beyond `type`/`enum`)                                              |
 *
 * EVERYTHING ELSE IS REJECTED at registration (unknown keyword, out-of-type
 * keyword, unknown type name, malformed keyword value — including `format`,
 * `const`, `anyOf`/`oneOf`/`allOf`, `not`, `if`/`then`/`else`, `$ref`/
 * `definitions`, `uniqueItems`, `multipleOf`, `propertyNames`,
 * `patternProperties`, `contains`, boolean subschemas). Fail-closed: an
 * unrecognized construct must never be silently ignored, because a silently
 * ignored constraint is a hole between what the model was told and what the
 * dispatcher enforces.
 *
 * Structural rules: `properties` maps name → schema object; `items` is a
 * SINGLE schema object (no tuple form); `additionalProperties` is
 * `false`/`true`/schema object; `enum` is a non-empty array of VALUES (not
 * schemas — its entries are never recursively checked); `type` is one type
 * name or an array of them; `pattern` is a regex of at most [MAX_PATTERN_LENGTH]
 * characters that must compile (server-supplied patterns are bounded by the
 * length cap to limit ReDoS exposure of the validation thread; patterns are
 * matched unanchored, per JSON Schema semantics).
 *
 * The subset check runs at descriptor construction (single enforcement
 * point — "unknown keyword 拒绝注册" is satisfied because a descriptor with
 * an out-of-subset schema cannot be constructed, from any source).
 */
@Suppress("TooManyFunctions") // one small leaf checker per subset keyword; splitting fragments the contract
object ToolSchema {
    const val TYPE_OBJECT = "object"
    const val TYPE_ARRAY = "array"
    const val TYPE_STRING = "string"
    const val TYPE_NUMBER = "number"
    const val TYPE_INTEGER = "integer"
    const val TYPE_BOOLEAN = "boolean"

    val ALL_TYPES: Set<String> =
        setOf(TYPE_OBJECT, TYPE_ARRAY, TYPE_STRING, TYPE_NUMBER, TYPE_INTEGER, TYPE_BOOLEAN)

    /** Standard JSON Schema annotation keywords: allowed, ignored, never enforced. */
    val ANNOTATION_KEYWORDS: Set<String> =
        setOf("description", "title", "default", "examples", "\$comment")

    /** Server/MCP-supplied patterns longer than this are rejected (bounded ReDoS exposure). */
    const val MAX_PATTERN_LENGTH = 256

    /**
     * Catastrophic-backtracking heuristic: a group whose content carries an
     * unbounded quantifier (`+`/`*`) while the group ITSELF is unboundedly
     * quantified — the classic `(a+)+` / `(.*)*` shapes. Bounded repetition
     * (`{n,m}`) in either position stays allowed. This is a heuristic
     * (character classes, escapes and alternation are not fully tracked);
     * together with the length cap it bounds the validation thread's
     * exposure to server-supplied patterns.
     */
    private val NESTED_UNBOUNDED_QUANTIFIER = Regex("\\((?:\\?<[=!])?[^()]*[+*][^()]*\\)[+*]")

    private val OBJECT_KEYS = setOf("properties", "required", "additionalProperties")
    private val ARRAY_KEYS = setOf("items", "minItems", "maxItems")
    private val STRING_KEYS = setOf("minLength", "maxLength", "pattern")
    private val NUMBER_KEYS = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")

    private val NON_NEGATIVE_INTEGER_KEYS = setOf("minItems", "maxItems", "minLength", "maxLength")
    private val NUMBER_BOUND_KEYS = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")

    private val TYPE_TO_KEYS: Map<String, Set<String>> =
        mapOf(
            TYPE_OBJECT to OBJECT_KEYS,
            TYPE_ARRAY to ARRAY_KEYS,
            TYPE_STRING to STRING_KEYS,
            TYPE_NUMBER to NUMBER_KEYS,
            TYPE_INTEGER to NUMBER_KEYS,
            TYPE_BOOLEAN to emptySet(),
        )

    /** Checks a schema against the subset; returns human-readable violations (empty = valid). */
    fun check(schema: JsonObject): List<String> {
        val violations = mutableListOf<String>()
        checkNode(schema, "$", violations)
        return violations
    }

    private val ALL_SUBSET_KEYWORDS: Set<String> =
        setOf("type", "enum") + ANNOTATION_KEYWORDS + OBJECT_KEYS + ARRAY_KEYS + STRING_KEYS + NUMBER_KEYS

    private fun checkNode(
        schema: JsonObject,
        path: String,
        out: MutableList<String>,
    ) {
        val declaredTypes = declaredTypes(schema, "$path.type", out).filter { it in ALL_TYPES }
        val contextKeys =
            if (declaredTypes.isEmpty() && !schema.containsKey("type")) {
                // No type declared: every type-specific keyword is potentially
                // applicable, so the UNION is allowed (unknown keywords still fail).
                OBJECT_KEYS + ARRAY_KEYS + STRING_KEYS + NUMBER_KEYS
            } else {
                declaredTypes.fold(emptySet<String>()) { acc, t -> acc + TYPE_TO_KEYS.getValue(t) }
            }
        schema.forEach { (key, value) ->
            when {
                key in ANNOTATION_KEYWORDS || key == "type" -> Unit

                key == "enum" -> checkEnum(value, "$path.enum", out)

                key in contextKeys -> checkKeyword(key, value, "$path.$key", out)

                // the keyword exists in the subset, but not for this type context
                key in ALL_SUBSET_KEYWORDS -> out.add("$path: keyword '$key' is not valid for the declared type(s)")

                else -> out.add("$path: unknown keyword '$key' (not in the tool schema subset)")
            }
        }
        checkCrossBounds(schema, path, out)
    }

    private fun checkKeyword(
        key: String,
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        when (key) {
            "properties" -> checkProperties(value, path, out)
            "required" -> checkRequired(value, path, out)
            "additionalProperties" -> checkAdditionalProperties(value, path, out)
            "items" -> checkSubschema(value, path, out)
            in NON_NEGATIVE_INTEGER_KEYS -> checkNonNegativeInt(value, path, out)
            "pattern" -> checkPattern(value, path, out)
            in NUMBER_BOUND_KEYS -> checkNumber(value, path, out)
            else -> Unit // unreachable: contextKeys is the union of these branches
        }
    }

    private fun declaredTypes(
        schema: JsonObject,
        path: String,
        out: MutableList<String>,
    ): List<String> {
        val type = schema["type"] ?: return emptyList()
        return when (type) {
            is JsonPrimitive -> {
                if (type.isString) {
                    listOf(type.content)
                } else {
                    out.add("$path: must be a string or an array of strings")
                    emptyList()
                }
            }

            is JsonArray -> {
                val names = mutableListOf<String>()
                type.forEach { element ->
                    if (element is JsonPrimitive && element.isString) {
                        names += element.content
                    } else {
                        out.add("$path: array entries must be type-name strings")
                    }
                }
                names
            }

            else -> {
                out.add("$path: must be a string or an array of strings")
                emptyList()
            }
        }.also { names ->
            names.forEach { name ->
                if (name !in ALL_TYPES) {
                    out.add("$path: unknown type '$name' (allowed: ${ALL_TYPES.sorted().joinToString()})")
                }
            }
        }
    }

    private fun checkSubschema(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val schema =
            value as? JsonObject
                ?: run {
                    out.add("$path: subschema must be a JSON object (boolean subschemas are not in the subset)")
                    return
                }
        checkNode(schema, path, out)
    }

    private fun checkProperties(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val props =
            value as? JsonObject
                ?: run {
                    out.add("$path: must be a JSON object of property name -> schema")
                    return
                }
        props.forEach { (name, subschema) ->
            if (name.isEmpty()) {
                out.add("$path: property names must not be empty")
            }
            checkSubschema(subschema, "$path[\"$name\"]", out)
        }
    }

    private fun checkRequired(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val list =
            value as? JsonArray
                ?: run {
                    out.add("$path: must be a JSON array of property-name strings")
                    return
                }
        list.forEach { element ->
            if (element !is JsonPrimitive || !element.isString) {
                out.add("$path: entries must be strings")
            }
        }
    }

    private fun checkAdditionalProperties(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        when (value) {
            is JsonPrimitive -> {
                if (!ToolJsonPrimitives.isBoolean(value)) {
                    out.add("$path: must be true, false, or a schema object")
                }
            }

            is JsonObject -> {
                checkNode(value, path, out)
            }

            else -> {
                out.add("$path: must be true, false, or a schema object")
            }
        }
    }

    private fun checkEnum(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val list =
            value as? JsonArray
                ?: run {
                    out.add("$path: must be a non-empty JSON array of allowed values")
                    return
                }
        if (list.isEmpty()) {
            out.add("$path: must not be empty")
        }
        // NOTE: entries are VALUES, not schemas — never recursively checked.
    }

    private fun checkNonNegativeInt(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val p = value as? JsonPrimitive
        // a numeric STRING ("3") is not a number: guard before longOrNull
        val n = if (p != null && ToolJsonPrimitives.isNumber(p)) p.longOrNull else null
        if (n == null || n < 0) {
            out.add("$path: must be a non-negative integer")
        }
    }

    private fun checkPattern(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val p = value as? JsonPrimitive
        when {
            p == null || !p.isString -> {
                out.add("$path: must be a string regex")
            }

            p.content.length > MAX_PATTERN_LENGTH -> {
                out.add("$path: pattern exceeds $MAX_PATTERN_LENGTH characters")
            }

            NESTED_UNBOUNDED_QUANTIFIER.containsMatchIn(p.content) -> {
                out.add("$path: pattern uses a nested unbounded quantifier (potential catastrophic backtracking)")
            }

            else -> {
                try {
                    Pattern.compile(p.content)
                } catch (e: PatternSyntaxException) {
                    out.add("$path: pattern does not compile: ${e.message}")
                }
            }
        }
    }

    private fun checkNumber(
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        if (value !is JsonPrimitive || !ToolJsonPrimitives.isNumber(value)) {
            out.add("$path: must be a JSON number")
        }
    }

    private fun checkCrossBounds(
        schema: JsonObject,
        path: String,
        out: MutableList<String>,
    ) {
        checkBoundPair(schema, path, out, "minimum", "maximum")
        checkBoundPair(schema, path, out, "exclusiveMinimum", "exclusiveMaximum")
        checkBoundPair(schema, path, out, "minLength", "maxLength")
    }

    private fun checkBoundPair(
        schema: JsonObject,
        path: String,
        out: MutableList<String>,
        lowKey: String,
        highKey: String,
    ) {
        val low = (schema[lowKey] as? JsonPrimitive)?.let { ToolJsonPrimitives.doubleOf(it) }
        val high = (schema[highKey] as? JsonPrimitive)?.let { ToolJsonPrimitives.doubleOf(it) }
        if (low != null && high != null && low > high) {
            out.add("$path: $lowKey ($low) must not be greater than $highKey ($high)")
        }
    }
}
