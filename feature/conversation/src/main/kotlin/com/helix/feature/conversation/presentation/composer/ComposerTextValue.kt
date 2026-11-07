package com.helix.feature.conversation.presentation.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Preserve selection and IME composition when the durable draft echoes the same text. */
fun synchronizeComposerText(
    value: TextFieldValue,
    text: String,
): TextFieldValue = if (value.text == text) value else TextFieldValue(text, TextRange(text.length))

/** Autocomplete replaces text and selection in one editor update, committing any IME composition. */
fun completeComposerText(
    value: TextFieldValue,
    query: AutocompleteQuery,
    suggestion: ComposerSuggestionItem,
): TextFieldValue {
    val (text, cursor) = ComposerCommandParser.applySuggestion(value.text, query, suggestion)
    return TextFieldValue(text, TextRange(cursor))
}
