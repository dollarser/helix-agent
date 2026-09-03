package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.TextAttachmentKind
import com.helix.core.workspace.ContentProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-049: [AttachmentClassifier] — closed classification of an imported file.
 *
 * The security-critical axis is the trusted, byte-derived [ContentProbe.Encoding]: only clean UTF-8
 * can ever become a materializable text attachment; UTF-16 / binary / empty are unsupported no
 * matter what the (untrusted) file name claims. The extension only picks a *label* (which text
 * kind, which closed unsupported category).
 */
class AttachmentClassifierTest {
    private fun utf8(name: String?) = AttachmentClassifier.classify(probe(ContentProbe.Encoding.UTF8), name)

    private fun utf16(name: String?) = AttachmentClassifier.classify(probe(ContentProbe.Encoding.UTF16), name)

    private fun binary(
        name: String?,
        mime: String,
    ) = AttachmentClassifier.classify(probe(ContentProbe.Encoding.BINARY, mime), name)

    private fun probe(
        encoding: ContentProbe.Encoding,
        mime: String = "application/octet-stream",
    ): ContentProbe.Result {
        val isText = encoding == ContentProbe.Encoding.UTF8 || encoding == ContentProbe.Encoding.UTF16
        return ContentProbe.Result(mime, encoding, isText, sizeBytes = 128L, sampleCrc32 = 0L, truncated = false)
    }

    @Test
    fun eachFirstBatchKindIsMaterializable() {
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.TXT), utf8("notes.txt"))
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.MARKDOWN), utf8("readme.md"))
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.CSV), utf8("data.csv"))
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.JSON), utf8("config.json"))
    }

    @Test
    fun textKindsMatchCaseInsensitively() {
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.TXT), utf8("NOTES.TXT"))
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.MARKDOWN), utf8("README.Markdown"))
    }

    @Test
    fun utf8TextOfAnUnlistedKindIsUnsupportedOther() {
        assertEquals(unsupported(AttachmentCategory.OTHER), utf8("app.log"))
        assertEquals(unsupported(AttachmentCategory.OTHER), utf8("noextension"))
        assertEquals(unsupported(AttachmentCategory.OTHER), utf8("trailing."))
        assertEquals(unsupported(AttachmentCategory.OTHER), utf8(null))
    }

    @Test
    fun utf16IsNeverATextAttachmentRegardlessOfName() {
        // Even a .txt name cannot promote UTF-16: the byte-derived encoding wins.
        assertEquals(unsupported(AttachmentCategory.TEXT_ENCODING), utf16("notes.txt"))
        assertEquals(unsupported(AttachmentCategory.TEXT_ENCODING), utf16(null))
    }

    @Test
    fun binaryImagesAreImageAttachmentsWithTheMagicDerivedMediaType() {
        // HXA-055 (ADR-0014 §4): a magic-confirmed image is the ImageAttachment branch; the
        // media type comes from the BYTES (the probe's magic table), never from the name — a
        // .png-named file whose bytes are JPEG is an image/jpeg attachment.
        assertEquals(
            AttachmentClassification.ImageAttachment("image/png"),
            binary("photo.png", "image/png"),
        )
        assertEquals(
            AttachmentClassification.ImageAttachment("image/jpeg"),
            binary("pic.jpg", "image/jpeg"),
        )
        assertEquals(
            AttachmentClassification.ImageAttachment("image/webp"),
            binary("pic.webp", "image/webp"),
        )
        assertEquals(
            AttachmentClassification.ImageAttachment("image/gif"),
            binary("anim.gif", "image/gif"),
        )
        // The bytes win over the name: a .png extension with jpeg bytes is an image/jpeg.
        assertEquals(
            AttachmentClassification.ImageAttachment("image/jpeg"),
            binary("renamed.png", "image/jpeg"),
        )
    }

    @Test
    fun aNonImageBinaryStaysUnsupportedOther() {
        assertEquals(unsupported(AttachmentCategory.OTHER), binary("archive.zip", "application/zip"))
        assertEquals(
            unsupported(AttachmentCategory.OTHER),
            binary("mystery.bin", "application/octet-stream"),
        )
    }

    @Test
    fun aNonClosedImageMediaTypeIsRejectedInTheClassification() {
        // The ImageAttachment media type is the closed ImageReference set — a synthetic probe
        // result with an image mime outside it must be refused by the value itself.
        var thrown: Throwable? = null
        try {
            AttachmentClassification.ImageAttachment("image/bmp")
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("an out-of-set image mediaType must be refused", thrown is IllegalArgumentException)
    }

    @Test
    fun binaryDocumentsAudioAndVideoMapToTheirClosedCategory() {
        // Office files are detected as application/zip by the probe; the extension says DOCUMENT.
        assertEquals(unsupported(AttachmentCategory.DOCUMENT), binary("report.docx", "application/zip"))
        assertEquals(unsupported(AttachmentCategory.DOCUMENT), binary("spec.pdf", "application/pdf"))
        assertEquals(unsupported(AttachmentCategory.AUDIO), binary("song.mp3", "application/octet-stream"))
        assertEquals(unsupported(AttachmentCategory.VIDEO), binary("clip.mp4", "application/octet-stream"))
    }

    @Test
    fun anEmptyFileIsUnsupportedOther() {
        val empty = ContentProbe.Result("application/octet-stream", ContentProbe.Encoding.EMPTY, false, 0L, 0L, false)
        assertEquals(unsupported(AttachmentCategory.OTHER), AttachmentClassifier.classify(empty, "empty.txt"))
    }

    @Test
    fun limitsAreTheClosedAdrValues() {
        assertEquals(10L shl 20, AttachmentClassifier.MAX_ATTACHMENT_BYTES)
        assertEquals(4, AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE)
    }

    private fun unsupported(category: AttachmentCategory) = AttachmentClassification.UnsupportedAttachment(category)
}
