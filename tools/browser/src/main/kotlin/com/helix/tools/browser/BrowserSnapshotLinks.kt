package com.helix.tools.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Reading projection only: no new observation, resolved URL, token minting or permission. */
internal object BrowserSnapshotLinks {
    const val MAX_LINKS = 40
    const val MAX_JSON_BYTES = 12 * 1024

    fun schema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("maxItems", JsonPrimitive(MAX_LINKS))
            put(
                "description",
                JsonPrimitive("Text view only: observed link labels and raw href attributes in document order."),
            )
            put(
                "items",
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("text", stringSchema(MAX_NODE_TEXT, "Bounded link label (UNTRUSTED)."))
                            put(
                                "href",
                                stringSchema(
                                    MAX_NODE_TEXT,
                                    "Bounded raw href for display only (UNTRUSTED); may be relative or incomplete.",
                                ),
                            )
                            put(
                                "token",
                                stringSchema(
                                    MAX_TOKEN,
                                    "Original observed link token; use browser.click, not a reconstructed URL.",
                                ),
                            )
                            put(
                                "hrefMayBeTruncated",
                                booleanSchema("Source bound reached or prefix shortened; do not navigate this prefix."),
                            )
                        },
                    required = listOf("text", "href", "token", "hrefMayBeTruncated"),
                ),
            )
        }

    fun project(out: SnapshotOutcome): Pair<JsonArray, Boolean> {
        val links = mutableListOf<JsonObject>()
        var bytes = 2 // JSON array brackets.
        var truncated = out.truncated
        for (node in out.nodes.asSequence().filter { it.href.isNotEmpty() }) {
            val link =
                buildJsonObject {
                    put("text", JsonPrimitive(prefix(node.text)))
                    put("href", JsonPrimitive(prefix(node.href)))
                    put("token", JsonPrimitive(node.token))
                    // The existing extractor clips href at 200 UTF-16 units without a per-field flag.
                    put("hrefMayBeTruncated", JsonPrimitive(node.href.length >= MAX_NODE_TEXT))
                }
            val addedBytes = link.toString().toByteArray(Charsets.UTF_8).size + if (links.isEmpty()) 0 else 1
            if (links.size == MAX_LINKS || bytes + addedBytes > MAX_JSON_BYTES) {
                truncated = true
                break
            }
            links.add(link)
            bytes += addedBytes
        }
        return JsonArray(links) to truncated
    }

    private fun prefix(value: String): String =
        value.take(MAX_NODE_TEXT).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
}
