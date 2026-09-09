package com.helix.app.ui

/** Presentation-only Markdown subset. HTML and image sources are never executed or fetched. */
internal data class MarkdownBlock(
    val kind: Kind,
    val text: String,
    val level: Int = 0,
) {
    enum class Kind { PARAGRAPH, HEADING, CODE, QUOTE, LIST, TABLE, RULE }
}

@Suppress("CyclomaticComplexMethod") // explicit branches for presentation-only Markdown tokens
internal fun markdownBlocks(source: String): List<MarkdownBlock> {
    val lines = source.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                val marker = trimmed.takeWhile { it == trimmed.first() }
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                blocks += MarkdownBlock(MarkdownBlock.Kind.CODE, code.joinToString("\n"))
            }

            line.isBlank() -> {
                Unit
            }

            trimmed.matches(Regex("(---+|\\*\\*\\*+|___+)")) -> {
                blocks += MarkdownBlock(MarkdownBlock.Kind.RULE, "")
            }

            trimmed.matches(Regex("#{1,6} .*")) -> {
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MarkdownBlock(MarkdownBlock.Kind.HEADING, trimmed.drop(level + 1), level)
            }

            trimmed.startsWith("> ") -> {
                blocks += MarkdownBlock(MarkdownBlock.Kind.QUOTE, trimmed.drop(2))
            }

            trimmed.matches(Regex("([-*+] |\\d+[.)] ).*")) -> {
                val list = if (trimmed.first() in "-*+") "• " + trimmed.drop(2) else trimmed
                blocks += MarkdownBlock(MarkdownBlock.Kind.LIST, list)
            }

            '|' in line && i + 1 < lines.size && tableDivider(lines[i + 1]) -> {
                val table = mutableListOf(line)
                i += 2
                while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) table += lines[i++]
                i--
                blocks += MarkdownBlock(MarkdownBlock.Kind.TABLE, table.joinToString("\n"))
            }

            else -> {
                blocks += MarkdownBlock(MarkdownBlock.Kind.PARAGRAPH, line)
            }
        }
        i++
    }
    return blocks
}

private fun tableDivider(line: String): Boolean {
    val cells = line.trim().trim('|').split('|')
    return cells.size >= 2 && cells.all { it.trim().matches(Regex(":?-{3,}:?")) }
}
