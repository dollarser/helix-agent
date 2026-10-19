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
            val first = result.isEmpty()
            var end = minOf(start + if (first) 16 else 64, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            val boundary =
                (end - 1 downTo start + if (first) 4 else (end - start) / 2).firstOrNull {
                    text[it].isWhitespace() || text[it] in "。！？；;，" ||
                        (text[it] in ",：:" && !numericSeparator(text, it)) ||
                        (text[it] in ".!?" && (it + 1 == text.length || text[it + 1].isWhitespace()))
                }
            if (boundary != null && end < text.length) end = boundary + 1
            end = wholeAsciiToken(text, start, end)
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

    private fun numericSeparator(
        text: String,
        index: Int,
    ): Boolean = index > 0 && index + 1 < text.length && text[index - 1].isDigit() && text[index + 1].isDigit()

    private fun token(
        text: String,
        index: Int,
    ): Boolean {
        val character = text[index]
        return (character.code < 128 && (character.isLetterOrDigit() || character in "._-'")) ||
            (character in ",:：" && numericSeparator(text, index))
    }

    private fun wholeAsciiToken(
        text: String,
        start: Int,
        proposed: Int,
    ): Int {
        if (proposed == text.length || !token(text, proposed - 1) || !token(text, proposed)) return proposed
        var before = proposed
        while (before > start && token(text, before - 1)) before--
        return if (before >= start + 4) {
            before
        } else {
            var after = proposed
            while (after < text.length && after < start + 64 && token(text, after)) after++
            after
        }
    }
}
