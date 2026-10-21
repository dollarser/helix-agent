package com.helix.app.knowledge

import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeMarkdownTest {
    @Test fun exportKeepsSavedBodyAndFrozenProvenanceWithoutGeneratedDraft() {
        val note = KnowledgeNote("n", "Title", "Saved body", 0, 0, generatedDraft = "Unreviewed suggestion")
        val source = KnowledgeNoteSource("n", "s", "Source", "https://example.com", 0, "Evidence", "abc", "READY")
        val text = knowledgeMarkdown(note, listOf(source))
        listOf("Saved body", "https://example.com", "Content SHA256: abc", "> Evidence", "Status: DRAFT").forEach {
            assertTrue(text.contains(it))
        }
        assertFalse(text.contains("Unreviewed suggestion"))
    }

    @Test fun evidenceLinesRemainQuotedAndManualNotesNeedNoSource() {
        val note = KnowledgeNote("n", "Title", "Body", 0, 0)
        assertTrue(knowledgeMarkdown(note, emptyList()).contains("Body"))
        val source = KnowledgeNoteSource("n", "s", "Source", "", 0, "first\n# injected heading", "abc", "READY")
        assertTrue(knowledgeMarkdown(note, listOf(source)).contains("> first\n> # injected heading"))
    }
}
