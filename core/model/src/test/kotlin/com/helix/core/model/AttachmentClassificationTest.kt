package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun classificationIsExactlyTheClosedDataBranches() {
        val text: AttachmentClassification =
            AttachmentClassification.TextAttachment(TextAttachmentKind.CSV)
        val image: AttachmentClassification =
            AttachmentClassification.ImageAttachment("image/png")
        val unsupported: AttachmentClassification =
            AttachmentClassification.UnsupportedAttachment(AttachmentCategory.DOCUMENT)
        // An exhaustive `when` (no `else`) only compiles when these are the sealed branches.
        val branch =
            when (text) {
                is AttachmentClassification.TextAttachment -> "text"
                is AttachmentClassification.ImageAttachment -> "image"
                is AttachmentClassification.UnsupportedAttachment -> "unsupported"
            }
        assertEquals("text", branch)
        // The branches are data-carrying and value-equal.
        assertEquals(text, AttachmentClassification.TextAttachment(TextAttachmentKind.CSV))
        assertEquals(image, AttachmentClassification.ImageAttachment("image/png"))
        assertEquals(
            unsupported,
            AttachmentClassification.UnsupportedAttachment(AttachmentCategory.DOCUMENT),
        )
    }

    @Test
    fun theImageAttachmentMediaTypeIsClosedToTheImageReferenceSet() {
        for (mediaType in ImageReference.MEDIA_TYPES) {
            val image = AttachmentClassification.ImageAttachment(mediaType)
            assertEquals(mediaType, (image as AttachmentClassification.ImageAttachment).mediaType)
        }
        var thrown: Throwable? = null
        try {
            AttachmentClassification.ImageAttachment("image/bmp")
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("an out-of-set image mediaType must be refused", thrown is IllegalArgumentException)
    }
}
