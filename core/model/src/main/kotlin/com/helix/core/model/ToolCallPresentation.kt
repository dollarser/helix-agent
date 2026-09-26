package com.helix.core.model

/**
 * Model-authored display metadata for one ToolCall.
 *
 * It is never authority: execution, effect, approval and status remain Harness-owned facts.
 */
data class ToolCallPresentation(
    val modelIntent: String?,
) {
    init {
        modelIntent?.let { intent ->
            require(intent.isNotBlank() && intent.length <= MAX_INTENT_LENGTH)
            require(intent.none(::isForbiddenCharacter))
        }
    }

    companion object {
        const val MAX_INTENT_LENGTH = 160
        val EMPTY = ToolCallPresentation(null)

        fun isForbiddenCharacter(char: Char): Boolean =
            char.isISOControl() ||
                char.category in
                setOf(CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)
    }
}
