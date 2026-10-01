package com.helix.app.ui.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ComposerTextValueTest {
    @Test fun selectionIsAfterTheEntireCommandAndTrailingSpace() {
        val input = TextFieldValue("/", TextRange(1))
        val query = requireNotNull(ComposerCommandParser.parseQuery(input.text, input.selection.end))
        val option = ComposerCommandParser.filterSuggestions(query).single { it.id == "slash:plan" }
        val result = completeComposerText(input, query, option)
        assertEquals("/plan ", result.text)
        assertEquals(TextRange(6), result.selection)
    }

    @Test fun echoedDraftDoesNotResetSelectionOrImeComposition() {
        val value = TextFieldValue("输入正文", TextRange(2), TextRange(0, 2))
        assertSame(value, synchronizeComposerText(value, value.text))
        assertEquals(TextRange(0, 2), synchronizeComposerText(value, value.text).composition)
    }

    @Test fun externalReplacementAndClearMoveCursorInsideTheNewText() {
        val old = TextFieldValue("old draft", TextRange(3), TextRange(0, 3))
        val replaced = synchronizeComposerText(old, "new🙂")
        assertEquals(TextRange("new🙂".length), replaced.selection)
        assertNull(replaced.composition)
        assertEquals(TextFieldValue("", TextRange.Zero), synchronizeComposerText(old, ""))
    }

    @Test fun completionPreservesTaskSuffixAndCommitsComposingText() {
        val value = TextFieldValue("/pl 保留🙂", TextRange(3), TextRange(1, 3))
        val query = requireNotNull(ComposerCommandParser.parseQuery(value.text, value.selection.end))
        val suggestion = ComposerCommandParser.filterSuggestions(query).single { it.id == "slash:plan" }
        val result = completeComposerText(value, query, suggestion)
        assertEquals("/plan  保留🙂", result.text)
        assertEquals(TextRange(6), result.selection)
        assertNull(result.composition)
    }

    @Test fun cursorOnlyChangesDoNotAlterDraftContent() {
        val value = TextFieldValue("/act task", TextRange(4, 9))
        assertSame(value, synchronizeComposerText(value, "/act task"))
    }
}
