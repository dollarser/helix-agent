package com.helix.extensions.mcp

/**
 * Ambiguous adjacent/repeated names require binding search, not a polynomial DP claim.
 * Every branch and compared character spends the discovery's shared work budget.
 * Explicit frames and URI spans bound memory to the template size, without recursion or copied values.
 */
internal class McpTemplateBindingSearch(
    private val template: SimpleTemplate,
    private val uri: String,
    private val budget: McpTemplateMatchingBudget,
    private val checkCurrent: () -> Unit,
) {
    private val starts = IntArray(template.variableCount) { -1 }
    private val ends = IntArray(template.variableCount)
    private val frames = ArrayDeque<Frame>()
    private var sinceYield = 0

    suspend fun matches(): Boolean {
        checkCurrent()
        kotlinx.coroutines.yield()
        push(0, 0)
        while (frames.isNotEmpty()) {
            spend()
            val frame = frames.last()
            if (frame.tokenIndex == template.tokens.size) {
                if (frame.uriIndex == uri.length) return true
                pop()
            } else if (frame.binding >= 0) {
                advanceBinding(frame)
            } else {
                advanceFixed(frame)
            }
        }
        return false
    }

    private fun advanceBinding(frame: Frame) {
        val end = frame.candidateEnd
        if (end < 0) {
            pop()
        } else {
            val width = scalarUnitWidth(uri, end)
            frame.candidateEnd = if (width == 0) -1 else end + width
            starts[frame.binding] = frame.uriIndex
            ends[frame.binding] = end
            push(frame.tokenIndex + 1, end)
        }
    }

    private suspend fun advanceFixed(frame: Frame) {
        if (frame.attempted) {
            pop()
        } else {
            frame.attempted = true
            val width = fixedWidth(template.tokens[frame.tokenIndex], frame.uriIndex)
            if (width >= 0) push(frame.tokenIndex + 1, frame.uriIndex + width)
        }
    }

    private suspend fun fixedWidth(
        token: SimpleTemplateToken,
        index: Int,
    ): Int =
        when (token) {
            is SimpleTemplateToken.Literal -> if (index < uri.length && uri[index] == token.value) 1 else -1
            is SimpleTemplateToken.Variable -> matchingSpanWidth(token.id, index)
        }

    private suspend fun matchingSpanWidth(
        id: Int,
        index: Int,
    ): Int {
        val width = ends[id] - starts[id]
        if (index + width > uri.length) return -1
        val equal =
            (0 until width).all { offset ->
                spend()
                uri[starts[id] + offset] == uri[index + offset]
            }
        return if (equal) width else -1
    }

    private fun push(
        tokenIndex: Int,
        uriIndex: Int,
    ) {
        val variable = template.tokens.getOrNull(tokenIndex) as? SimpleTemplateToken.Variable
        val binding = variable?.id?.takeIf { starts[it] < 0 } ?: -1
        frames.addLast(Frame(tokenIndex, uriIndex, binding))
    }

    private fun pop() {
        val frame = frames.removeLast()
        if (frame.binding >= 0) starts[frame.binding] = -1
    }

    private suspend fun spend() {
        budget.consume(1)
        if (++sinceYield >= 1024) {
            sinceYield = 0
            checkCurrent()
            kotlinx.coroutines.yield()
        }
    }

    private class Frame(
        val tokenIndex: Int,
        val uriIndex: Int,
        val binding: Int,
        var candidateEnd: Int = uriIndex,
        var attempted: Boolean = false,
    )
}
