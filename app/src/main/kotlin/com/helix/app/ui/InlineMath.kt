package com.helix.app.ui

/** Delimiters only; KaTeX remains the parser. Protected Markdown and unfinished input stay literal. */
internal data class InlineMath(
    val start: Int,
    val end: Int,
    val source: String,
)

internal fun inlineMath(source: String): List<InlineMath> {
    if (source.length > 64 * 1024) return emptyList()
    val result = mutableListOf<InlineMath>()
    val lastLink = source.lastIndexOf("](")
    var index = 0
    while (index < source.length && result.size < 8) {
        val protected = protectedMathEnd(source, index, lastLink)
        val part = if (protected == null) mathAt(source, index) else null
        if (part != null) result += part
        index = protected ?: part?.end ?: (index + 1)
    }
    return result
}

private fun protectedMathEnd(
    source: String,
    index: Int,
    lastLink: Int,
): Int? =
    when {
        source.startsWith("$$", index) -> {
            val end = source.indexOf("$$", index + 2)
            if (end < 0) source.length else end + 2
        }

        source[index] == '`' -> {
            val count = source.substring(index).takeWhile { it == '`' }.length
            val end = source.indexOf("`".repeat(count), index + count)
            if (end < 0) source.length else end + count
        }

        source[index] == '[' && index <= lastLink -> {
            val label = source.indexOf("](", index + 1)
            val end = if (label < 0) -1 else source.indexOf(')', label + 2)
            if (end < 0) source.length else end + 1
        }

        else -> {
            null
        }
    }

private fun mathAt(
    source: String,
    index: Int,
): InlineMath? {
    val escaped = index > 0 && source[index - 1] == '\\'
    val slash = !escaped && source.startsWith("\\(", index)
    val dollar = !escaped && source[index] == '$'
    if (!slash && !dollar) return null
    val opening = if (slash) 2 else 1
    val closing = if (slash) "\\)" else "$"
    val start = index + opening
    val window = source.substring(start, minOf(source.length, start + 1024 + closing.length))
    val found = window.indexOf(closing)
    val body = if (found < 0) "" else window.substring(0, found)
    val valid = body.isNotBlank() && body.length <= 1024 && '\n' !in body && (slash || dollarMathBody(body))
    return if (valid) InlineMath(index, start + found + closing.length, body) else null
}

private fun dollarMathBody(body: String): Boolean {
    val operator = body.any { it in "\\=+^_" }
    return body == body.trim() && body.any { it.isLetter() || it in "\\=+^_" } &&
        (body.none { it.isWhitespace() } || operator)
}
