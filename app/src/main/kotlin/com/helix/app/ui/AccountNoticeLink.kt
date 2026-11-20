package com.helix.app.ui

import java.net.URI

internal data class AccountNoticeLink(
    val start: Int,
    val end: Int,
    val url: String,
)

/** Plain text only. No HTML interpretation, implicit www scheme, or non-web URI handling. */
internal fun accountNoticeLinks(body: String): List<AccountNoticeLink> =
    noticeWebPattern
        .findAll(body)
        .mapNotNull { match ->
            val url = trimNoticeLink(trimAdjacentNoticeText(match.value))
            val uri = runCatching { URI(url) }.getOrNull()
            val host = runCatching { uri?.toURL()?.host }.getOrNull()
            if (uri?.scheme?.lowercase() in setOf("http", "https") && !host.isNullOrBlank()) {
                AccountNoticeLink(match.range.first, match.range.first + url.length, url)
            } else {
                null
            }
        }.toList()

private val noticeWebPattern =
    Regex(
        "(?<![A-Za-z0-9_:/])https?://[^\\s<>\"'\\p{Cntrl}，。！？；：、（）【】]+",
        RegexOption.IGNORE_CASE,
    )

// Adjacent prose after an ASCII bare host is not part of its authority.
// Unicode paths remain opaque: without punctuation there is no reliable prose/URL boundary.
private fun trimAdjacentNoticeText(value: String): String {
    val authority = value.substringAfter("://").takeWhile { it !in "/?#" }
    if (authority.none { it.code > 127 } || authority.firstOrNull()?.code?.let { it > 127 } == true) return value
    val asciiEnd = authority.indexOfFirst { it.code > 127 }
    val prefix = authority.take(asciiEnd)
    return if (prefix.contains('.') && !prefix.endsWith('.') && value.substringAfter("://") == authority) {
        value.substringBefore("://") + "://" + prefix
    } else {
        value
    }
}

private fun trimNoticeLink(value: String): String {
    var end = value.length
    val punctuation = ".,;:!?，。！？；：、"
    val pairs = mapOf(')' to '(', ']' to '[', '}' to '{', '）' to '（', '】' to '【')
    val counts = value.groupingBy { it }.eachCount().toMutableMap()
    while (end > 0) {
        val last = value[end - 1]
        val open = pairs[last]
        val unmatched = open != null && counts.getOrDefault(last, 0) > counts.getOrDefault(open, 0)
        if (last !in punctuation && !unmatched) break
        counts[last] = counts.getOrDefault(last, 0) - 1
        end--
    }
    return value.substring(0, end)
}
