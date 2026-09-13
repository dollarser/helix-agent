package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.TextAttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * HXA-049: [AttachmentMaterializer] — the fail-closed, re-verified materialization of a bound
 * attachment. Covers the hash re-verification (missing / tampered → block), the re-derivation of
 * the classification from the bytes on disk (never the name or the import-time label), the bounded
 * UTF-8 inline view with the full-content SHA, and the closed unsupported category.
 */
class AttachmentMaterializerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(
        text: String,
        name: String = "note.txt",
    ): Path {
        val file = tmp.root.toPath().resolve(name)
        Files.write(file, text.toByteArray(Charsets.UTF_8))
        return file
    }

    private fun writeBytes(
        bytes: ByteArray,
        name: String,
    ): Path {
        val file = tmp.root.toPath().resolve(name)
        Files.write(file, bytes)
        return file
    }

    /** A minimal .docx (a zip with `word/document.xml`) — a REAL container, not a stub. */
    private fun docxBytes(documentXml: String): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            out.toByteArray()
        }

    // ── Confirmed-UTF-8 text materializes, bounded, with the full-content hash ─────────────

    @Test
    fun aConfirmedUtf8TextFileMaterializesAsBoundedText() {
        val body = "hello attachment\n"
        val file = write(body)
        val result = AttachmentMaterializer.materialize(file, sha(body.toByteArray()), "note.txt")

        val text = result as AttachmentMaterialization.Text
        assertEquals("note.txt", text.fileName)
        assertEquals(TextAttachmentKind.TXT, text.kind)
        assertEquals(body, text.content)
        assertEquals(sha(body.toByteArray()), text.sha256)
        assertFalse("a small file is not truncated", text.truncated)
        assertEquals(body.toByteArray().size.toLong(), text.sizeBytes)
    }

    @Test
    fun eachFirstBatchKindMaterializesFromItsExtension() {
        fun kind(name: String): TextAttachmentKind =
            (AttachmentMaterializer.materialize(write("x", name), sha("x".toByteArray()), name))
                .let { it as AttachmentMaterialization.Text }
                .kind
        assertEquals(TextAttachmentKind.CSV, kind("data.csv"))
        assertEquals(TextAttachmentKind.JSON, kind("cfg.json"))
        assertEquals(TextAttachmentKind.MARKDOWN, kind("readme.md"))
        assertEquals(TextAttachmentKind.TXT, kind("plain.txt"))
    }

    // ── Extracted-document kinds (P0-B): text is extracted; the SHA binds the RAW file ────

    @Test
    fun anHtmlFileMaterializesAsExtractedTextWithTheRawFileBound() {
        val body = "<html><body><p>Hello HTML</p></body></html>\n"
        val file = write(body, "page.html")
        val result = AttachmentMaterializer.materialize(file, sha(body.toByteArray(Charsets.UTF_8)), "page.html")

        val text = result as AttachmentMaterialization.Text
        assertEquals(TextAttachmentKind.HTML, text.kind)
        assertTrue("the extracted text must carry the visible content", text.content.contains("Hello HTML"))
        assertEquals("the SHA binds the FULL raw file", sha(body.toByteArray(Charsets.UTF_8)), text.sha256)
        assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), text.sizeBytes)
    }

    @Test
    fun aPdfFileMaterializesAsExtractedTextWithTheRawFileBound() {
        val pdfBytes =
            (
                "%PDF-1.4\n4 0 obj\n<< /Length 30 >>\nstream\n" +
                    "BT /F1 12 Tf (PDF hello) Tj ET\nendstream\nendobj\n%%EOF\n"
            ).encodeToByteArray(Charsets.ISO_8859_1)
        val file = writeBytes(pdfBytes, "doc.pdf")
        val result = AttachmentMaterializer.materialize(file, sha(pdfBytes), "doc.pdf")

        val text = result as AttachmentMaterialization.Text
        assertEquals(TextAttachmentKind.PDF, text.kind)
        assertTrue("the extracted PDF text must be present", text.content.contains("PDF hello"))
        assertEquals("the SHA binds the FULL raw PDF, not the extracted view", sha(pdfBytes), text.sha256)
        assertEquals(pdfBytes.size.toLong(), text.sizeBytes)
    }

    @Test
    fun aDocxFileMaterializesAsExtractedTextWithTheRawFileBound() {
        val bytes = docxBytes("<w:p><w:r><w:t>Docx hello</w:t></w:r></w:p>")
        val file = writeBytes(bytes, "doc.docx")
        val result = AttachmentMaterializer.materialize(file, sha(bytes), "doc.docx")

        val text = result as AttachmentMaterialization.Text
        assertEquals(TextAttachmentKind.DOCX, text.kind)
        assertTrue("the extracted DOCX text must be present", text.content.contains("Docx hello"))
        assertEquals(sha(bytes), text.sha256)
        assertEquals(bytes.size.toLong(), text.sizeBytes)
    }

    @Test
    fun anExtractedDocumentLargerThanTheInlineBoundIsTruncated() {
        // A DOCX whose extracted text exceeds the 8 KiB inline bound: the view is a prefix, the
        // SHA still binds the full raw file, and truncated is reported.
        val paragraph = "<w:p><w:r><w:t>${"x".repeat(40)}</w:t></w:r></w:p>"
        val bytes = docxBytes("<w:body>" + paragraph.repeat(300) + "</w:body>") // ~120 KB of extracted text
        val file = writeBytes(bytes, "big.docx")
        val result = AttachmentMaterializer.materialize(file, sha(bytes), "big.docx")

        val text = result as AttachmentMaterialization.Text
        assertTrue("over-cap extracted text is truncated", text.truncated)
        assertEquals(
            "the extracted view is exactly the inline bound",
            AttachmentMaterializer.MAX_INLINE_TEXT_BYTES,
            text.content.length,
        )
        assertEquals(sha(bytes), text.sha256)
        assertEquals(bytes.size.toLong(), text.sizeBytes)
    }

    @Test
    fun aFileLargerThanTheInlineBoundIsTruncatedButKeepsTheFullSha() {
        // 9000 single-byte UTF-8 chars: over the 8 KiB inline bound, so the view is a prefix.
        val body = "a".repeat(9000)
        val file = write(body)
        val result = AttachmentMaterializer.materialize(file, sha(body.toByteArray()), "big.txt")

        val text = result as AttachmentMaterialization.Text
        assertTrue("over-cap is truncated", text.truncated)
        assertEquals(
            "the inline view is exactly the probed prefix",
            "a".repeat(AttachmentMaterializer.MAX_INLINE_TEXT_BYTES),
            text.content,
        )
        assertEquals(
            "the SHA binds the FULL content, not the truncated view",
            sha(body.toByteArray()),
            text.sha256,
        )
        assertEquals(9000L, text.sizeBytes)
    }

    // ── Fail-closed re-verification: missing / tampered block the send ─────────────────────

    @Test
    fun aMissingFileFailsClosedAsUnavailable() {
        val result =
            AttachmentMaterializer.materialize(
                tmp.root.toPath().resolve("does-not-exist.txt"),
                sha("x".toByteArray()),
                "gone.txt",
            )
        assertEquals(AttachmentMaterialization.Unavailable("gone.txt"), result)
    }

    @Test
    fun aTamperedFileFailsClosedAsTampered() {
        val original = "original bytes".toByteArray()
        val file = writeBytes(original, "note.txt")
        // Re-verify against the snapshot taken at import time, then the on-disk bytes change.
        val boundSha = sha(original)
        Files.write(file, "tampered bytes".toByteArray(Charsets.UTF_8))

        val result = AttachmentMaterializer.materialize(file, boundSha, "note.txt")
        val tampered = result as AttachmentMaterialization.Tampered
        assertEquals(boundSha, tampered.expectedSha256)
        assertEquals(sha("tampered bytes".toByteArray(Charsets.UTF_8)), tampered.actualSha256)
    }

    // ── Classification is re-derived from the bytes, never the name ────────────────────────

    @Test
    fun binaryBytesNamedTxtAreUnsupportedNotText() {
        // A NUL byte makes the probe report BINARY regardless of the .txt extension: the bytes
        // win, so a tampered or mislabeled file can never be promoted to materializable text.
        val file = writeBytes(byteArrayOf(0x00, 0x01, 0x02), "fake.txt")
        val result = AttachmentMaterializer.materialize(file, sha(byteArrayOf(0x00, 0x01, 0x02)), "fake.txt")

        assertEquals(
            "byte-derived classification outranks the .txt name",
            AttachmentMaterialization.Unsupported("fake.txt", AttachmentCategory.OTHER),
            result,
        )
    }

    @Test
    fun aUtf16FileIsUnsupportedTextEncoding() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE)
        val file = writeBytes(bytes, "notes.txt")
        val result = AttachmentMaterializer.materialize(file, sha(bytes), "notes.txt")

        assertEquals(AttachmentMaterialization.Unsupported("notes.txt", AttachmentCategory.TEXT_ENCODING), result)
    }

    @Test
    fun aBinaryImageMaterializesAsAnImageWithTheRawSnapshotBound() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val file = writeBytes(png, "photo.png")
        val result = AttachmentMaterializer.materialize(file, sha(png), "photo.png")

        // HXA-055: the verified raw image is the Image branch — the send gate rewrites the
        // facts to the normalized artifact's; here (raw-only) dimensions are 0.
        assertEquals(
            AttachmentMaterialization.Image("photo.png", "image/png", sha(png), png.size.toLong(), 0, 0),
            result,
        )
    }

    @Test
    fun aUtf8FileWithADisallowedExtensionIsUnsupportedOther() {
        // Valid UTF-8, but the extension names none of the four first-batch kinds.
        val file = write("some text", "data.xyz")
        val result = AttachmentMaterializer.materialize(file, sha("some text".toByteArray()), "data.xyz")

        assertEquals(AttachmentMaterialization.Unsupported("data.xyz", AttachmentCategory.OTHER), result)
    }
}
