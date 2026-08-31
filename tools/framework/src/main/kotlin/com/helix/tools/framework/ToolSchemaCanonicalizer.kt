package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical serialization of a tool schema (doc 02 section 7: stable schema
 * hash; doc 10 section 4.3: schema change invalidates approvals).
 *
 * Rules:
 * - object keys are sorted lexicographically (key order in the wire form is
 *   not semantically meaningful — two servers may emit the same schema in
 *   different orders and MUST hash identically);
 * - array element order is preserved (arrays are ordered);
 * - literals are re-rendered from the PARSED tree (kotlinx's canonical
 *   literal form: quoted+escaped strings, bare numbers/booleans, `null`);
 * - the form is minified (no insignificant whitespace).
 *
 * The hash therefore is stable for the same parsed schema, and different for
 * any semantic difference (a changed type, bound, property or array element).
 *
 * NOTE: this canonicalizer is for the tool SCHEMA contract. The canonical
 * encoding of tool ARGUMENTS (approval hashing, ADR-0001 lineage) is a
 * separate, STRICTER implementation owned by the Tool framework for the
 * approval-hash work and must not be assumed to be this one. (HXA-031
 * delivers the JSON Schema subset + value validation, not that encoder.)
 */
internal object ToolSchemaCanonicalizer {
    fun canonicalize(element: JsonElement): String =
        when (element) {
            is JsonObject -> {
                element.keys
                    .sorted()
                    .joinToString(",", "{", "}") { key ->
                        JsonPrimitive(key).toString() + ":" +
                            canonicalize(element.getValue(key))
                    }
            }

            is JsonArray -> {
                element.joinToString(",", "[", "]") { canonicalize(it) }
            }

            // JsonPrimitive also covers JsonNull (and the internal JsonLiteral
            // base of the parsed tree): toString() is kotlinx's canonical,
            // minified, escaped literal form.
            is JsonPrimitive -> {
                element.toString()
            }
        }
}
