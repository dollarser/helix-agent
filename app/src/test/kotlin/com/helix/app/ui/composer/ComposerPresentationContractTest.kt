package com.helix.app.ui.composer

import com.helix.app.chat.ChatSubmissionErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** User-facing input labels stay independent of the internal cache implementation. */
class ComposerPresentationContractTest {
    @Test fun clearCommandFallbackDescribesInputNotDraftStorage() {
        val suggestions =
            ComposerCommandParser.filterSuggestions(
                requireNotNull(ComposerCommandParser.parseQuery("/clear")),
            )
        assertEquals("Clear current input", suggestions.single().detail)
    }

    @Test fun removedDraftConflictHasNoSubmissionErrorMapping() {
        assertNull(ChatSubmissionErrorMapper.stringResFor("DRAFT_CHANGED"))
    }

    @Test fun conversationResourcesHaveNoDraftStatusInAnySupportedLanguage() {
        val removed =
            setOf(
                "message_revision_saving",
                "message_revision_saved",
                "message_revision_save_failed",
                "chat_submission_rejected_draft_changed",
            )
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        for (variant in listOf("values", "values-en", "values-zh-rCN")) {
            val nodes =
                factory
                    .newDocumentBuilder()
                    .parse(File("src/main/res/$variant/strings.xml"))
                    .getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val name = element.getAttribute("name")
                assertFalse("$variant still declares $name", name in removed)
                val conversationLabel =
                    name.startsWith("chat_") || name.startsWith("message_revision_") ||
                        name.startsWith("session_shared_")
                if (conversationLabel) {
                    val text = element.textContent
                    assertFalse(
                        "$variant/$name exposes cache terminology",
                        text.contains("草稿") || text.contains("draft", true),
                    )
                }
            }
        }
    }
}
