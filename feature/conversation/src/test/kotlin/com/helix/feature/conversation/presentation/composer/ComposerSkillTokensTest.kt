package com.helix.feature.conversation.presentation.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSkillTokensTest {
    @Test fun labelsAreSingleAtomicEditorCharactersAndNeverPartOfSubmittedText() {
        val editor = ComposerSkillTokens.editorText("任务", 2)
        assertEquals(4, editor.length)
        assertEquals("任务", ComposerSkillTokens.plainText(editor, listOf(0, 1)))
        assertEquals(listOf(0 to 0, 1 to 1), ComposerSkillTokens.retainedPositions(editor, editor, listOf(0, 1)))
        val displayed = ComposerSkillTokens.transform(editor, listOf("browser", "document reading"), Color.Black)
        assertEquals(" browser  document reading 任务", displayed.text.text)
        val firstEnd = displayed.offsetMapping.originalToTransformed(1)
        for (offset in 0..firstEnd) {
            assertTrue(displayed.offsetMapping.transformedToOriginal(offset) in 0..1)
        }
        assertEquals(editor.length, displayed.offsetMapping.transformedToOriginal(displayed.text.length))
    }

    @Test fun deletingOneEditorCharacterRemovesWholeSkillButKeepsOrdinaryText() {
        val editor = ComposerSkillTokens.editorText("保留文本", 2)
        val deleted = editor.removeRange(1, 2)
        assertEquals(listOf(0 to 0), ComposerSkillTokens.retainedPositions(editor, deleted, listOf(0, 1)))
        assertEquals("保留文本", ComposerSkillTokens.plainText(deleted, listOf(0)))
        assertTrue(ComposerSkillTokens.retainedPositions(editor, editor.drop(2), listOf(0, 1)).isEmpty())
    }

    @Test fun pastedMarkersStayOrdinaryTextAndCannotCreateActivations() {
        val before = ComposerSkillTokens.editorText("text", 1)
        val after = before + ComposerSkillTokens.marker(0) + ComposerSkillTokens.marker(3)
        val retained = ComposerSkillTokens.retainedPositions(before, after, listOf(0))
        assertEquals(listOf(0 to 0), retained)
        assertEquals(
            "text${ComposerSkillTokens.marker(0)}${ComposerSkillTokens.marker(3)}",
            ComposerSkillTokens.plainText(after, listOf(0)),
        )
        val restored = ComposerSkillTokens.editorText("${ComposerSkillTokens.marker(0)}", 1)
        assertEquals("${ComposerSkillTokens.marker(0)}", ComposerSkillTokens.plainText(restored, listOf(0)))
    }

    @Test fun slashAfterSkillDiscoversSkillsButNotBuiltinControls() {
        val editor = ComposerSkillTokens.editorText("/read", 1)
        val query = requireNotNull(ComposerCommandParser.parseQuery(editor))
        val skill = ComposerSuggestionItem("skill:read", "/read", "Read", ComposerSuggestionType.SKILL, "")
        assertEquals(listOf(skill), ComposerCommandParser.filterSuggestions(query, skills = listOf(skill)))
        assertTrue(ComposerCommandParser.filterSuggestions(query).isEmpty())
    }

    @Test fun whitespaceCommitsLeadingInvocationOnlyAfterImeCompositionEnds() {
        val before = TextFieldValue("/read", composition = TextRange(1, 5))
        val composing = TextFieldValue("/read 保留正文", composition = TextRange(1, 6))
        assertNull(ComposerSkillTokens.committedInvocation(before, composing, emptyList()))
        val committed = composing.copy(composition = null)
        val query = requireNotNull(ComposerSkillTokens.committedInvocation(composing, committed, emptyList()))
        assertEquals("read", query.token)
        val option = ComposerSuggestionItem("skill:read", "/read", null, ComposerSuggestionType.SKILL, "")
        assertEquals("保留正文", completeComposerText(committed, query, option).text)
        assertNull(
            ComposerSkillTokens.committedInvocation(committed, committed.copy(selection = TextRange(0)), emptyList()),
        )
    }

    @Test fun builtinNamesAndPathsAreNeverImplicitSkillInvocations() {
        listOf("/compact task", "/plan task", "/tmp/read task", "text /read task").forEach { text ->
            assertNull(ComposerSkillTokens.committedInvocation(TextFieldValue(), TextFieldValue(text), emptyList()))
        }
        val query = requireNotNull(ComposerSkillTokens.leadingInvocation("/read  task", emptyList()))
        assertEquals(6, query.rangeEnd)
    }

    @Test fun offsetMappingStaysMonotonicWithEmojiAndMultipleSkills() {
        val editor = ComposerSkillTokens.editorText("你好 🐈", 2)
        val result = ComposerSkillTokens.transform(editor, listOf("技能一", "技能二"), Color.Black)
        val offsets = (0..result.text.length).map(result.offsetMapping::transformedToOriginal)
        assertTrue(offsets.zipWithNext().all { (first, second) -> first <= second })
        for (offset in editor.indices) {
            assertEquals(
                offset,
                result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(offset)),
            )
        }
    }
}
