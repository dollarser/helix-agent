package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HXA-049: the closed attachment-classification contract (ADR-0014 §2). These are pure model
 * types; the classifier that produces them is [com.helix.feature.files.AttachmentClassifier].
 * The important guarantee is that the sets are CLOSED: a new unsupported format must never force a
 * new category or a new text kind.
 */
class AttachmentClassificationTest {
    @Test
    fun categoryIsClosedToTheFiveAdrValues() {
        assertEquals(
            setOf("TEXT_ENCODING", "DOCUMENT", "AUDIO", "VIDEO", "OTHER"),
            AttachmentCategory.entries.map(AttachmentCategory::name).toSet(),
        )
    }

    @Test
    fun textKindIsClosedToTheFourFirstBatchKinds() {
        assertEquals(
            setOf("TXT", "MARKDOWN", "CSV", "JSON"),
            TextAttachmentKind.entries.map(TextAttachmentKind::name).toSet(),
        )
    }

    @Test
    fun classificationIsExactlyTwoDataBranches() {
        val text: AttachmentClassification =
            AttachmentClassification.TextAttachment(TextAttachmentKind.CSV)
        val unsupported: AttachmentClassification =
            AttachmentClassification.UnsupportedAttachment(AttachmentCategory.DOCUMENT)
        // An exhaustive `when` (no `else`) only compiles when these are the two sealed branches.
        val branch =
            when (text) {
                is AttachmentClassification.TextAttachment -> "text"
                is AttachmentClassification.UnsupportedAttachment -> "unsupported"
            }
        assertEquals("text", branch)
        // The branches are data-carrying and value-equal.
        assertEquals(text, AttachmentClassification.TextAttachment(TextAttachmentKind.CSV))
        assertEquals(
            unsupported,
            AttachmentClassification.UnsupportedAttachment(AttachmentCategory.DOCUMENT),
        )
    }
}
