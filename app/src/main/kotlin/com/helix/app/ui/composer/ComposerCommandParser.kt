package com.helix.app.ui.composer

/**
 * Types of suggestions available in the conversation composer.
 */
enum class ComposerSuggestionType {
    SLASH_COMMAND,
    CONNECTOR,
    SKILL,
    FILE,
    ARTIFACT,
}

/**
 * A candidate item displayed in the composer autocomplete popup.
 */
data class ComposerSuggestionItem(
    val id: String,
    val label: String,
    val detail: String? = null,
    val type: ComposerSuggestionType,
    val insertText: String,
)

/**
 * Query extracted from the composer text field at the current cursor position.
 */
data class AutocompleteQuery(
    val triggerChar: Char,
    val token: String,
    val rangeStart: Int,
    val rangeEnd: Int,
)

/**
 * Built-in slash command definitions.
 */
data class SlashCommandSpec(
    val command: String,
    val descriptionKey: String,
    val defaultDescription: String,
) {
    val id: String get() = "slash:$command"
}

/**
 * Pure parsing and filtering logic for composer mentions and slash commands.
 */
object ComposerCommandParser {
    val BUILTIN_SLASH_COMMANDS =
        listOf(
            SlashCommandSpec(
                command = "plan",
                descriptionKey = "chat_command_plan_desc",
                defaultDescription = "Switch to read-only Plan mode",
            ),
            SlashCommandSpec(
                command = "act",
                descriptionKey = "chat_command_act_desc",
                defaultDescription = "Switch to normal Act mode",
            ),
            SlashCommandSpec(
                command = "goal",
                descriptionKey = "chat_command_goal_desc",
                defaultDescription = "Switch to autonomous Goal mode",
            ),
            SlashCommandSpec(
                command = "compact",
                descriptionKey = "chat_command_compact_desc",
                defaultDescription = "Compact current conversation context",
            ),
            SlashCommandSpec(
                command = "clear",
                descriptionKey = "chat_command_clear_desc",
                defaultDescription = "Clear composer input draft",
            ),
            SlashCommandSpec(
                command = "help",
                descriptionKey = "chat_command_help_desc",
                defaultDescription = "Show available commands and tips",
            ),
        )

    /**
     * Extracts an [AutocompleteQuery] from the input text relative to the cursor.
     * Returns null if no active trigger is detected.
     */
    @Suppress("ReturnCount")
    fun parseQuery(
        text: String,
        cursor: Int = text.length,
    ): AutocompleteQuery? {
        val safeCursor = cursor.coerceIn(0, text.length)
        if (safeCursor == 0) return null

        val prefix = text.substring(0, safeCursor)
        val lastSlash = prefix.lastIndexOf('/')
        val lastAt = prefix.lastIndexOf('@')

        // Choose the closest trigger before cursor
        val triggerIndex = maxOf(lastSlash, lastAt)
        if (triggerIndex < 0) return null

        val triggerChar = prefix[triggerIndex]

        // Slash command must be at the very start of input, or after a newline
        if (triggerChar == '/') {
            if (triggerIndex != 0 && prefix[triggerIndex - 1] != '\n') {
                return null
            }
        }

        // At-mention must be at start of input, or preceded by whitespace
        if (triggerChar == '@') {
            if (triggerIndex > 0 && !prefix[triggerIndex - 1].isWhitespace()) {
                return null
            }
        }

        // Token is between trigger and cursor; cannot contain whitespace or another trigger
        val token = prefix.substring(triggerIndex + 1)
        if (token.any { it.isWhitespace() || it == '/' || it == '@' }) {
            return null
        }

        return AutocompleteQuery(
            triggerChar = triggerChar,
            token = token,
            rangeStart = triggerIndex,
            rangeEnd = safeCursor,
        )
    }

    /**
     * Filters available items by the given query token.
     */
    fun filterSuggestions(
        query: AutocompleteQuery,
        commands: List<SlashCommandSpec> = BUILTIN_SLASH_COMMANDS,
        connectors: List<ComposerSuggestionItem> = emptyList(),
        files: List<ComposerSuggestionItem> = emptyList(),
        artifacts: List<ComposerSuggestionItem> = emptyList(),
    ): List<ComposerSuggestionItem> {
        val lowerToken = query.token.lowercase()

        return when (query.triggerChar) {
            '/' -> {
                commands
                    .filter { it.command.lowercase().contains(lowerToken) }
                    .map {
                        ComposerSuggestionItem(
                            id = it.id,
                            label = "/${it.command}",
                            detail = it.defaultDescription,
                            type = ComposerSuggestionType.SLASH_COMMAND,
                            insertText = "/${it.command} ",
                        )
                    }
            }

            '@' -> {
                val allItems = connectors + files + artifacts
                if (lowerToken.isEmpty()) {
                    allItems
                } else {
                    allItems.filter {
                        it.label.lowercase().contains(lowerToken) ||
                            (it.detail?.lowercase()?.contains(lowerToken) == true)
                    }
                }
            }

            else -> {
                emptyList()
            }
        }
    }

    /**
     * Replaces the triggered query token with the chosen suggestion's insertText.
     */
    fun applySuggestion(
        originalText: String,
        query: AutocompleteQuery,
        suggestion: ComposerSuggestionItem,
    ): Pair<String, Int> {
        val before = originalText.substring(0, query.rangeStart)
        val after = originalText.substring(query.rangeEnd.coerceAtMost(originalText.length))
        val newText = before + suggestion.insertText + after
        val newCursor = (before + suggestion.insertText).length
        return Pair(newText, newCursor)
    }
}
