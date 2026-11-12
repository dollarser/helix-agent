package com.helix.feature.conversation.presentation.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText

/** Each selected skill occupies one editor character. The markers never leave the editor. */
internal object ComposerSkillTokens {
    private const val FIRST = '\uE000'
    private const val LAST = '\uE003'

    fun marker(index: Int): Char = FIRST + index

    fun isMarker(char: Char): Boolean = char in FIRST..LAST

    fun plainText(
        text: String,
        positions: List<Int>,
    ): String = text.filterIndexed { index, _ -> index !in positions }

    fun editorText(
        text: String,
        count: Int,
    ): String = (0 until count.coerceAtMost(4)).joinToString("") { marker(it).toString() } + text

    /** Only existing token positions survive an edit. Pasted characters never create activations. */
    fun retainedPositions(
        before: String,
        after: String,
        positions: List<Int>,
    ): List<Pair<Int, Int>> {
        val commonPrefix = before.zip(after).takeWhile { (left, right) -> left == right }.size
        val commonSuffix =
            before
                .drop(commonPrefix)
                .reversed()
                .zip(after.drop(commonPrefix).reversed())
                .takeWhile { (left, right) -> left == right }
                .size
        val oldEnd = before.length - commonSuffix
        val delta = after.length - before.length
        return positions.mapIndexedNotNull { index, position ->
            when {
                position < commonPrefix -> index to position
                position >= oldEnd -> index to (position + delta)
                else -> null
            }
        }
    }

    /** A committed leading invocation consumes its single delimiter, never the following task body. */
    @Suppress("ReturnCount")
    fun leadingInvocation(
        text: String,
        positions: List<Int>,
    ): AutocompleteQuery? {
        val start = text.indices.firstOrNull { it !in positions } ?: return null
        if (text[start] != '/') return null
        val end = (start until text.length).firstOrNull { text[it].isWhitespace() } ?: return null
        val query = ComposerCommandParser.parseQuery(text, end) ?: return null
        if (query.rangeStart != start || query.token.isEmpty()) return null
        return query.copy(rangeEnd = end + 1)
    }

    fun committedInvocation(
        before: TextFieldValue,
        after: TextFieldValue,
        positions: List<Int>,
    ): AutocompleteQuery? {
        val contentCommitted = before.text != after.text || before.composition != null
        if (!contentCommitted || after.composition != null) return null
        return if (ComposerCommandParser.leadingCommand(plainText(after.text, positions)) == null) {
            leadingInvocation(after.text, positions)
        } else {
            null
        }
    }

    /** Display labels are not editable characters and cannot change the skill's identity. */
    fun transform(
        text: String,
        labels: List<String>,
        foreground: Color,
        positions: List<Int> = labels.indices.toList(),
    ): TransformedText {
        val result = AnnotatedString.Builder()
        val forward = IntArray(text.length + 1)
        val reverse = mutableListOf(0)
        text.forEachIndexed { index, char ->
            forward[index] = result.length
            val tokenIndex = positions.indexOf(index)
            val label = labels.getOrNull(tokenIndex)
            val rendered = label?.let { " $it " } ?: char.toString()
            val start = result.length
            result.append(rendered)
            if (label != null) {
                result.addStyle(SpanStyle(color = foreground, fontWeight = FontWeight.Medium), start, result.length)
                result.addStringAnnotation("skill", tokenIndex.toString(), start, result.length)
            }
            rendered.indices.forEach { offset ->
                reverse.add(if (offset + 1 < rendered.length / 2) index else index + 1)
            }
        }
        forward[text.length] = result.length
        return TransformedText(
            result.toAnnotatedString(),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = forward[offset.coerceIn(forward.indices)]

                override fun transformedToOriginal(offset: Int): Int = reverse[offset.coerceIn(reverse.indices)]
            },
        )
    }
}
