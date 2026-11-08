package com.helix.extensions.mcp

/** Scalar `{name}` subset with ASCII letters/digits/underscore names; repeated names require equal encoded spans. */
internal suspend fun matchesSimpleTemplate(
    template: String,
    uri: String,
    budget: McpTemplateMatchingBudget = McpTemplateMatchingBudget(),
    checkCurrent: () -> Unit = {},
): Boolean {
    val parsed = if (template.length <= 4096 && uri.length <= 4096) parseSimpleTemplate(template) else null
    if (parsed == null || !templateShapeMatches(parsed.tokens, uri, budget, checkCurrent)) return false
    return !parsed.repeated || McpTemplateBindingSearch(parsed, uri, budget, checkCurrent).matches()
}

/** Rolling DP is exact for unique names and an inexpensive feasibility test for repeated names. */
private suspend fun templateShapeMatches(
    tokens: List<SimpleTemplateToken>,
    uri: String,
    budget: McpTemplateMatchingBudget,
    checkCurrent: () -> Unit,
): Boolean {
    var previous = BooleanArray(uri.length + 1).also { it[0] = true }
    for ((row, token) in tokens.withIndex()) {
        if (row % 8 == 0) {
            checkCurrent()
            kotlinx.coroutines.yield()
        }
        budget.consume(uri.length.coerceAtLeast(1))
        val next = if (token is SimpleTemplateToken.Variable) previous.copyOf() else BooleanArray(uri.length + 1)
        for (index in uri.indices) {
            advanceShape(token, uri, index, previous, next)
        }
        previous = next
    }
    return previous[uri.length]
}

private fun advanceShape(
    token: SimpleTemplateToken,
    uri: String,
    index: Int,
    previous: BooleanArray,
    next: BooleanArray,
) {
    when (token) {
        is SimpleTemplateToken.Variable -> {
            val width = scalarUnitWidth(uri, index)
            if (next[index] && width > 0) next[index + width] = true
        }

        is SimpleTemplateToken.Literal -> {
            next[index + 1] = previous[index] && uri[index] == token.value
        }
    }
}

internal sealed interface SimpleTemplateToken {
    data class Literal(
        val value: Char,
    ) : SimpleTemplateToken

    data class Variable(
        val id: Int,
    ) : SimpleTemplateToken
}

internal data class SimpleTemplate(
    val tokens: List<SimpleTemplateToken>,
    val variableCount: Int,
    val repeated: Boolean,
)

private fun parseSimpleTemplate(template: String): SimpleTemplate? {
    val names = mutableMapOf<String, Int>()
    val tokens = mutableListOf<SimpleTemplateToken>()
    var offset = 0
    var count = 0
    for (match in Regex("\\{([A-Za-z0-9_]+)\\}").findAll(template)) {
        val literal = template.substring(offset, match.range.first)
        if (!validTemplateLiteral(literal)) return null
        literal.forEach { tokens.add(SimpleTemplateToken.Literal(it)) }
        val id = names.getOrPut(match.groupValues[1]) { names.size }
        tokens.add(SimpleTemplateToken.Variable(id))
        count++
        offset = match.range.last + 1
    }
    val tail = template.substring(offset)
    return if (count == 0 || !validTemplateLiteral(tail)) {
        null
    } else {
        tail.forEach { tokens.add(SimpleTemplateToken.Literal(it)) }
        SimpleTemplate(tokens, names.size, count > names.size)
    }
}

/** Percent escapes belong wholly to a literal or an expansion, never across their boundary. */
private fun validTemplateLiteral(literal: String): Boolean {
    var index = 0
    while (index < literal.length) {
        val value = literal[index]
        val malformedEscape = value == '%' && scalarUnitWidth(literal, index) != 3
        if (value == '{' || value == '}' || malformedEscape) return false
        index += if (value == '%') 3 else 1
    }
    return true
}

/** An expansion consumes whole ASCII unreserved characters or percent-encoded octets, never raw reserved text. */
internal fun scalarUnitWidth(
    uri: String,
    index: Int,
): Int =
    when {
        index >= uri.length -> 0
        uri[index] in 'a'..'z' || uri[index] in 'A'..'Z' || uri[index] in '0'..'9' || uri[index] in "-._~" -> 1
        uri[index] == '%' && index + 2 < uri.length && uri[index + 1].isAsciiHex() && uri[index + 2].isAsciiHex() -> 3
        else -> 0
    }

private fun Char.isAsciiHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Shared by the whole discovery, including DP and binding search; never reset per template or page. */
internal class McpTemplateMatchingBudget {
    private var remainingCells = 16777216L

    fun consume(cells: Int) {
        require(cells >= 0)
        if (cells > remainingCells) throw McpResourceException(McpResourceFailure.MATCH_BUDGET_EXCEEDED)
        remainingCells -= cells
    }
}
