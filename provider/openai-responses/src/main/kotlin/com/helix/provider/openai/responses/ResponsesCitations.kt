package com.helix.provider.openai.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URISyntaxException

/** Native citations travel through TextDelta, so normal transcript persistence retains their links. */
internal class ResponsesCitations(
    private val fail: (String) -> Nothing,
) {
    private val sources = linkedMapOf<String, String>()
    private val emitted = mutableSetOf<String>()

    fun add(element: JsonElement?) {
        val annotation = element as? JsonObject ?: fail("missing citation annotation")
        if (string(annotation["type"]) != "url_citation") return
        val url = string(annotation["url"]) ?: fail("citation missing URL")
        val uri =
            try {
                URI(url)
            } catch (_: URISyntaxException) {
                fail("invalid citation URL")
            }
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            fail("unsupported citation URL")
        }
        if (url.any { it.isISOControl() }) fail("invalid citation URL character")
        if (sources.size >= MAX_SOURCES && url !in sources) fail("too many citation sources")
        val title = string(annotation["title"])?.takeIf { it.isNotBlank() } ?: uri.host
        sources.putIfAbsent(url, title)
    }

    fun addMessage(item: JsonObject) {
        (item["content"] as? JsonArray)?.forEach { element ->
            val part = element as? JsonObject ?: return@forEach
            if (string(part["type"]) == "output_text") {
                (part["annotations"] as? JsonArray)?.forEach(::add)
            }
        }
    }

    fun flush(): String? {
        val pending = sources.filterKeys { it !in emitted }
        if (pending.isEmpty()) return null
        emitted.addAll(pending.keys)
        return pending.entries.joinToString(separator = "\n", prefix = "\n\n") { (url, title) ->
            "- [${escapeTitle(title)}](${escapeUrl(url)})"
        }
    }

    private fun escapeTitle(title: String): String =
        buildString {
            title.take(MAX_TITLE_LENGTH).forEach { char ->
                when {
                    char.isWhitespace() || char.isISOControl() -> append(' ')
                    char in "[]()" -> append("［］（）"["[]()".indexOf(char)])
                    char in "\\`*_{}<>!#|" -> append(' ')
                    else -> append(char)
                }
            }
        }

    private fun escapeUrl(url: String): String {
        // The chat renderer accepts plain destinations and ends them at the first closing parenthesis.
        val normalized = url.substringBefore(':').lowercase() + url.substring(url.indexOf(':'))
        return normalized
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("<", "%3C")
            .replace(">", "%3E")
            .replace("\\", "%5C")
    }

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val MAX_SOURCES = 256
        const val MAX_TITLE_LENGTH = 300
    }
}
