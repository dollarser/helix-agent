package com.helix.core.model

/** Bounded immutable text snapshot; links are spoken by visible label, never fetched. */
object ReadAloudText {
    const val MAX_TEXT = 20_000

    fun chunks(markdown: String): List<String> {
        require(markdown.length <= MAX_TEXT) { "TEXT_TOO_LONG" }
        val text =
            markdown
                .replace(Regex("""!?\[([^\n\]]*)]\([^\n)]*\)"""), "$1")
                .replace(Regex("""(?m)^\s{0,3}(?:#{1,6}|>|[-*])\s+"""), "")
                .replace("```", "")
                .replace("`", "")
                .replace("**", "")
                .trim()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 64, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            val boundary =
                (end - 1 downTo start + (end - start) / 2).firstOrNull {
                    text[it].isWhitespace() || text[it] in "。！？；;" ||
                        (text[it] in ".!?" && (it + 1 == text.length || text[it + 1].isWhitespace()))
                }
            if (boundary != null && end < text.length) end = boundary + 1
            text
                .substring(start, end)
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(result::add)
            start = end
        }
        require(result.isNotEmpty()) { "EMPTY_TEXT" }
        return result
    }
}
