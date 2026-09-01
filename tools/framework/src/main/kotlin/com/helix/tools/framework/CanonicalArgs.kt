package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canonical encoding of tool ARGUMENTS for the approval hash (roadmap HXA-034/035; the
 * strict args encoder the [ToolSchemaCanonicalizer] KDoc explicitly reserved for the
 * approval-hash work).
 *
 * Rules (identical shape discipline to the schema canonicalizer so the two encoders are
 * cross-auditable):
 * - object keys are sorted lexicographically;
 * - array element order is preserved (arrays are ordered);
 * - literals are re-rendered from the PARSED tree (kotlinx's canonical, minified, escaped
 *   literal form);
 * - the form is minified (no insignificant whitespace).
 *
 * This is the ONLY encoder that may produce the bytes a dispatcher hashes into
 * `ApprovalBinding.argsHash`: the same parsed argument object must always yield the same
 * bytes, so a re-issued identical action produces an identical hash (and the same-turn
 * denial invariant, security doc section 7.3, keys on it).
 *
 * Public (HXA-036) because the storage layer persists the SAME canonical bytes: the
 * `tool_calls.argsJson` column is this encoder's output and `tool_calls.argsHash` is the
 * SHA-256 of it (architecture doc section 9.1/9.2), so the persisted row and the approval
 * binding can never disagree on what the arguments were.
 */
object CanonicalArgs {
    fun canonicalize(element: JsonElement): String =
        when (element) {
            is JsonPrimitive -> {
                element.toString()
            }

            is JsonObject -> {
                element.keys
                    .sorted()
                    .joinToString(",", "{", "}") { key ->
                        JsonPrimitive(key).toString() + ":" + canonicalize(element.getValue(key))
                    }
            }

            is JsonArray -> {
                element.joinToString(",", "[", "]") { canonicalize(it) }
            }
        }
}
