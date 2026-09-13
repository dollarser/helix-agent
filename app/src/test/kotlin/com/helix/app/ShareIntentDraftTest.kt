package com.helix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PX-06: [ShareIntentDraft.buildDraft] — the pure share-draft core. Shared `text/plain`
 * pre-fills the composer; image references stage as images; the closed office/research
 * document set (PDF / DOCX / HTML) stages as attachments. Anything unadvertised is an empty
 * draft (a no-op) — the manifest is the first gate, this is the second. Unit-testable on the
 * JVM because the core takes plain strings + a URI list (no [android.content.Intent]).
 */
class ShareIntentDraftTest {
    private val send = "android.intent.action.SEND"
    private val sendMultiple = "android.intent.action.SEND_MULTIPLE"

    @Test
    fun plainTextBecomesTheComposerPrefill() {
        val d = ShareIntentDraft.buildDraft(send, "text/plain", "  https://x.example  ", emptyList())
        assertEquals("https://x.example", d.text)
        assertTrue(d.imageUris.isEmpty())
        assertTrue(d.fileUris.isEmpty())
        assertFalse(d.isEmpty)
    }

    @Test
    fun aBlankTextShareIsAnEmptyDraft() {
        assertTrue(ShareIntentDraft.buildDraft(send, "text/plain", "   ", emptyList()).isEmpty)
    }

    @Test
    fun imageSharesStageAsImages() {
        val single = ShareIntentDraft.buildDraft(send, "image/jpeg", null, listOf("content://img/1"))
        assertNull(single.text)
        assertEquals(listOf("content://img/1"), single.imageUris)
        assertTrue(single.fileUris.isEmpty())
        val multi = ShareIntentDraft.buildDraft(sendMultiple, "image/jpeg", null, listOf("a", "b"))
        assertEquals(listOf("a", "b"), multi.imageUris)
    }

    @Test
    fun aSharedPdfFileStagesAsAnAttachment() {
        val d = ShareIntentDraft.buildDraft(send, "application/pdf", null, listOf("content://doc/1"))
        assertNull(d.text)
        assertTrue(d.imageUris.isEmpty())
        assertEquals(listOf("content://doc/1"), d.fileUris)
        assertFalse(d.isEmpty)
    }

    @Test
    fun aSharedDocxAndHtmlFileStageAsAttachments() {
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(listOf("c1"), ShareIntentDraft.buildDraft(send, docx, null, listOf("c1")).fileUris)
        assertEquals(listOf("c2"), ShareIntentDraft.buildDraft(send, "text/html", null, listOf("c2")).fileUris)
    }

    @Test
    fun aMultiPdfShareStagesEveryFile() {
        val d = ShareIntentDraft.buildDraft(sendMultiple, "application/pdf", null, listOf("p1", "p2"))
        assertEquals(listOf("p1", "p2"), d.fileUris)
    }

    @Test
    fun anHtmlShareWithNoFilePrefillsTheText() {
        // A webpage shared as text/html with only EXTRA_TEXT (no file) pre-fills the composer.
        val d = ShareIntentDraft.buildDraft(send, "text/html", "<html>x</html>", emptyList())
        assertEquals("<html>x</html>", d.text)
        assertTrue(d.fileUris.isEmpty())
    }

    @Test
    fun anUnadvertisedTypeYieldsAnEmptyDraft() {
        // The manifest gates these out; a stray share of an unhandled type is a no-op draft.
        assertTrue(ShareIntentDraft.buildDraft(send, "video/mp4", null, listOf("v")).isEmpty)
        assertTrue(ShareIntentDraft.buildDraft(send, "application/zip", null, listOf("z")).isEmpty)
    }

    @Test
    fun aNonSendActionDoesNotPrefillText() {
        // Only ACTION_SEND carries composer text; the pre-fill must not leak from other actions.
        val d = ShareIntentDraft.buildDraft("android.intent.action.MAIN", "text/plain", "hello", emptyList())
        assertNull(d.text)
    }
}
