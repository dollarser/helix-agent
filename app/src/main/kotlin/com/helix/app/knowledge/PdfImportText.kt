package com.helix.app.knowledge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Bounded, reconstructible preview index; never the authority for original PDF bytes. */
internal class PdfImportText(
    private val budget: Int = MAX_KNOWLEDGE_TEXT,
) {
    private val text = StringBuilder()
    private val pages = mutableListOf<JsonObject>()
    private var truncated = false
    val hasCapacity get() = text.length < budget

    fun append(
        page: Int,
        result: JsonObject,
    ) {
        val body = result.getValue("text").jsonPrimitive.content
        val marker = "\n\n## PDF page $page\n"
        text.append(knowledgeTextPrefix(marker, (budget - text.length).coerceAtLeast(0)))
        val start = text.length
        text.append(knowledgeTextPrefix(body, (budget - start).coerceAtLeast(0)))
        val partial = result.getValue("truncated").jsonPrimitive.boolean || text.length - start < body.length
        truncated = truncated || partial
        pages +=
            buildJsonObject {
                put("page", page)
                put("start", start)
                put("end", text.length)
                put(
                    "state",
                    when {
                        body.isBlank() -> "no_text"
                        partial -> "partial"
                        else -> "text_extracted"
                    },
                )
                result["nextOffset"]?.jsonPrimitive?.intOrNull?.let { put("nextReadOffset", it) }
            }
    }

    fun result(
        total: Int,
        failure: String?,
    ): KnowledgePdfExtraction.Result {
        val limited = truncated || pages.size < total
        val reason = failure ?: if (limited) "text_limit" else "text_only"
        val coverage =
            buildJsonObject {
                put("totalPages", total)
                put("processedPages", pages.size)
                put("pages", JsonArray(pages))
                put("offsetUnit", "utf16")
                put("reason", reason)
                put("textOnly", true)
                put("truncated", limited)
            }
        return KnowledgePdfExtraction.Result(text.toString(), coverage, true)
    }
}
