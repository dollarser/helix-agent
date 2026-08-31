package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.regex.Pattern

/**
 * The result of validating one JSON value against a tool schema (HXA-031).
 *
 * [Invalid.reasons] carries EVERY detected violation (one reason per
 * constraint, recursing into children) with JSONPath-like locations, so the
 * dispatcher (HXA-035) can report a complete, model-usable error instead of
 * playing whack-a-mole with the model.
 */
sealed interface ToolSchemaValidation {
    /** The value satisfies every constraint of the schema. */
    data object Valid : ToolSchemaValidation

    /** One or more constraint violations; [reasons] is never empty. */
    data class Invalid(
        val reasons: List<String>,
    ) : ToolSchemaValidation
}

/**
 * Validates JSON VALUES against the tool schema subset (HXA-031; doc 02 §7.1
 * pipeline step "JSON Schema 验证").
 *
 * Semantics (JSON Schema subset, deterministic):
 * - `type`: the value must match at least one declared type (`integer`
 *   matches integral numbers only); without a `type`, the value is
 *   unconstrained by type;
 * - `enum`: structural (deep) equality with at least one entry;
 * - `object`: `required` presence, `properties` per-name validation,
 *   `additionalProperties` = false / true / subschema;
 * - `array`: `items` applied to EVERY element, `minItems`/`maxItems`;
 * - `string`: `minLength`/`maxLength` in UNICODE CODE POINTS (a 4-byte emoji
 *   counts 1), `pattern` matched UNANCHORED (JSON Schema semantics);
 * - `number`/`integer`: `minimum`/`maximum` inclusive,
 *   `exclusiveMinimum`/`exclusiveMaximum` exclusive (numeric bounds compared
 *   as doubles — bounds are tool contract, not a security boundary).
 *
 * The schema is expected to have passed [ToolSchema.check] at registration
 * (a malformed schema reaching validation would be a framework bug; it
 * reports an invalid result rather than silently skipping constraints).
 */
@Suppress("TooManyFunctions") // one focused checker per schema keyword; splitting fragments the validation flow
object ToolSchemaValidator {
    fun validate(
        schema: JsonObject,
        value: JsonElement,
    ): ToolSchemaValidation {
        val reasons = mutableListOf<String>()
        validateNode(schema, value, "$", reasons)
        return if (reasons.isEmpty()) ToolSchemaValidation.Valid else ToolSchemaValidation.Invalid(reasons)
    }

    private fun validateNode(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        checkType(schema, value, path, out)
        checkEnum(schema, value, path, out)
        when (value) {
            is JsonObject -> {
                validateObject(schema, value, path, out)
            }

            is JsonArray -> {
                validateArray(schema, value, path, out)
            }

            is JsonPrimitive -> {
                when {
                    value.isString -> validateStringConstraints(schema, value.content, path, out)
                    ToolJsonPrimitives.isNumber(value) -> validateNumberConstraints(schema, value, path, out)
                    else -> Unit
                }
            }

            is JsonNull -> {
                Unit
            }
        }
    }

    private fun checkType(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val type = schema["type"] ?: return
        val names = mutableListOf<String>()
        when (type) {
            is JsonPrimitive -> {
                if (type.isString) names += type.content
            }

            is JsonArray -> {
                type.forEach { element ->
                    if (element is JsonPrimitive && element.isString) {
                        names += element.content
                    }
                }
            }

            else -> {
                Unit
            }
        }
        if (names.isEmpty()) {
            // Fail-closed: a malformed type declaration (a framework bug —
            // registration should have caught it) must not skip constraints.
            out.add("$path: malformed type declaration in schema (validation failed closed)")
            return
        }
        if (names.none { it.matchesType(value) }) {
            out.add("$path: value is ${typeNameOf(value)}, expected ${names.distinct().joinToString(" | ")}")
        }
    }

    private fun String.matchesType(value: JsonElement): Boolean =
        when (this) {
            ToolSchema.TYPE_OBJECT -> value is JsonObject
            ToolSchema.TYPE_ARRAY -> value is JsonArray
            ToolSchema.TYPE_STRING -> value is JsonPrimitive && value.isString
            ToolSchema.TYPE_BOOLEAN -> value is JsonPrimitive && ToolJsonPrimitives.isBoolean(value)
            ToolSchema.TYPE_NUMBER -> value is JsonPrimitive && ToolJsonPrimitives.isNumber(value)
            ToolSchema.TYPE_INTEGER -> value is JsonPrimitive && ToolJsonPrimitives.isNumber(value) && isIntegral(value)
            else -> false
        }

    private fun isIntegral(value: JsonPrimitive): Boolean =
        value.longOrNull != null ||
            (value.doubleOrNull?.let { it.isFinite() && it == Math.floor(it) } ?: false)

    private fun typeNameOf(value: JsonElement): String =
        when (value) {
            is JsonObject -> {
                "object"
            }

            is JsonArray -> {
                "array"
            }

            is JsonNull -> {
                "null"
            }

            is JsonPrimitive -> {
                when {
                    value.isString -> "string"
                    ToolJsonPrimitives.isBoolean(value) -> "boolean"
                    ToolJsonPrimitives.isNumber(value) -> if (value.longOrNull != null) "integer" else "number"
                    else -> "null"
                }
            }
        }

    private fun checkEnum(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        out: MutableList<String>,
    ) {
        val list = schema["enum"] as? JsonArray ?: return
        if (!list.contains(value)) {
            out.add("$path: value is not one of the allowed enum values")
        }
    }

    private fun validateObject(
        schema: JsonObject,
        value: JsonObject,
        path: String,
        out: MutableList<String>,
    ) {
        (schema["required"] as? JsonArray)
            ?.filterIsInstance<JsonPrimitive>()
            ?.filter { it.isString }
            ?.forEach { required ->
                if (value[required.content] == null) {
                    out.add("$path: missing required property '${required.content}'")
                }
            }
        val properties = schema["properties"] as? JsonObject
        properties?.forEach { (name, subschema) ->
            val child = value[name] ?: return@forEach
            if (subschema is JsonObject) {
                validateNode(subschema, child, "$path[\"$name\"]", out)
            }
        }
        checkAdditional(schema, value, properties?.keys ?: emptySet(), path, out)
    }

    private fun checkAdditional(
        schema: JsonObject,
        value: JsonObject,
        known: Set<String>,
        path: String,
        out: MutableList<String>,
    ) {
        val additional = schema["additionalProperties"] ?: return
        val unknownKeys = value.keys.filter { it !in known }
        when (additional) {
            is JsonPrimitive -> {
                if (ToolJsonPrimitives.booleanOf(additional) == false) {
                    unknownKeys.forEach { key -> out.add("$path: additional property '$key' is not allowed") }
                }
            }

            is JsonObject -> {
                unknownKeys.forEach { key ->
                    validateNode(additional, value.getValue(key), "$path[\"$key\"]", out)
                }
            }

            else -> {
                Unit
            }
        }
    }

    private fun validateArray(
        schema: JsonObject,
        value: JsonArray,
        path: String,
        out: MutableList<String>,
    ) {
        (schema["minItems"] as? JsonPrimitive)?.longOrNull?.let { min ->
            if (value.size < min) {
                out.add("$path: array has ${value.size} items, minimum is $min")
            }
        }
        (schema["maxItems"] as? JsonPrimitive)?.longOrNull?.let { max ->
            if (value.size > max) {
                out.add("$path: array has ${value.size} items, maximum is $max")
            }
        }
        val items = schema["items"] as? JsonObject ?: return
        value.forEachIndexed { index, element ->
            validateNode(items, element, "$path[$index]", out)
        }
    }

    private fun validateStringConstraints(
        schema: JsonObject,
        text: String,
        path: String,
        out: MutableList<String>,
    ) {
        // JSON Schema length semantics: unicode code points, not UTF-16 units.
        val length = text.codePoints().count()
        (schema["minLength"] as? JsonPrimitive)?.longOrNull?.let { min ->
            if (length < min) {
                out.add("$path: string has $length code points, minimum is $min")
            }
        }
        (schema["maxLength"] as? JsonPrimitive)?.longOrNull?.let { max ->
            if (length > max) {
                out.add("$path: string has $length code points, maximum is $max")
            }
        }
        (schema["pattern"] as? JsonPrimitive)?.let { p ->
            if (p.isString && !Pattern.compile(p.content).matcher(text).find()) {
                out.add("$path: string does not match pattern '${p.content}'")
            }
        }
    }

    private fun validateNumberConstraints(
        schema: JsonObject,
        value: JsonPrimitive,
        path: String,
        out: MutableList<String>,
    ) {
        val d = value.doubleOrNull ?: return
        (schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { bound ->
            if (d < bound) {
                out.add("$path: value $d is less than minimum $bound")
            }
        }
        (schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { bound ->
            if (d > bound) {
                out.add("$path: value $d is greater than maximum $bound")
            }
        }
        (schema["exclusiveMinimum"] as? JsonPrimitive)?.doubleOrNull?.let { bound ->
            if (d <= bound) {
                out.add("$path: value $d is not greater than exclusive minimum $bound")
            }
        }
        (schema["exclusiveMaximum"] as? JsonPrimitive)?.doubleOrNull?.let { bound ->
            if (d >= bound) {
                out.add("$path: value $d is not less than exclusive maximum $bound")
            }
        }
    }
}
