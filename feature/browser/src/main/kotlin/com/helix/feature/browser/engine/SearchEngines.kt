package com.helix.feature.browser.engine

import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URLEncoder

/**
 * Represents a search engine provider with its URL template.
 */
data class SearchEngine(
    val id: String,
    val name: String,
    val searchUrlTemplate: String,
)

object SearchEngines {
    val GOOGLE = SearchEngine("google", "Google", "https://www.google.com/search?q=%s")
    val BING = SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s")
    val BAIDU = SearchEngine("baidu", "百度", "https://www.baidu.com/s?wd=%s")
    val DUCKDUCKGO = SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s")

    val ALL = listOf(GOOGLE, BING, BAIDU, DUCKDUCKGO)

    fun getById(id: String): SearchEngine = ALL.firstOrNull { it.id == id } ?: GOOGLE

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val IP_OR_LOCAL = Regex("^(localhost|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(:\\d+)?(/.*)?$")
    private val DOMAIN_REGEX = Regex("^[a-zA-Z0-9][-a-zA-Z0-9]*(\\.[a-zA-Z0-9][-a-zA-Z0-9]*)+(:\\d+)?(/.*)?$")

    /**
     * Intelligently resolves raw user input in the omnibox:
     * - Returns the URL untouched if it already has a supported scheme (http, https, about, data).
     * - Adds "https://" if it looks like a domain, IP, or localhost.
     * - Converts to a search query URL using [engine] if it contains spaces or is a plain search term.
     */
    fun resolveInput(
        raw: String,
        engine: SearchEngine = GOOGLE,
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val lowered = trimmed.lowercase()
        return when {
            lowered.startsWith("about:") ||
                lowered.startsWith("data:") ||
                SCHEME_REGEX.containsMatchIn(trimmed) -> trimmed

            trimmed.contains(" ") ||
                trimmed.contains("\t") ||
                trimmed.contains("\n") -> buildSearchUrl(trimmed, engine)

            IP_OR_LOCAL.matches(trimmed) || DOMAIN_REGEX.matches(trimmed) -> "https://$trimmed"

            !trimmed.contains(".") -> buildSearchUrl(trimmed, engine)

            else -> "https://$trimmed"
        }
    }

    private fun buildSearchUrl(
        query: String,
        engine: SearchEngine,
    ): String {
        val encoded =
            try {
                URLEncoder.encode(query, "UTF-8")
            } catch (
                @Suppress("SwallowedException") e: UnsupportedEncodingException,
            ) {
                query
            }
        return engine.searchUrlTemplate.replace("%s", encoded)
    }
}
