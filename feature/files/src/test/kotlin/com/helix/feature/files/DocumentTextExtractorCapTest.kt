package com.helix.feature.files

import com.helix.core.model.TextAttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The size caps of [DocumentTextExtractor] — the zip-bomb / inflate-bomb guards. A container
 * whose DECOMPRESSED content or extracted text exceeds the caps comes back as a bounded
 * [ExtractedText.Truncated] carrying the REAL prefix (never an OOM, never a fabricated value),
 * and a valid container without readable text is an empty [ExtractedText.Success], not
 * [ExtractedText.Unreadable]. The fixtures are real: a genuine zip with multi-MiB entry data and
 * a genuine zlib stream whose expansion is 8 MiB.
 */
class DocumentTextExtractorCapTest {
    private fun docx(documentXml: String): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            out.toByteArray()
        }

    private fun pdf(contentStream: String): String =
        "%PDF-1.4\n" +
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n" +
            "4 0 obj\n<< /Length ${contentStream.length} >>\nstream\n" +
            "$contentStream\n" +
            "endstream\nendobj\n" +
            "trailer\n<< /Root 1 0 R >>\n%%EOF\n"

    @Test
    fun aZipBombDocxIsBoundedTruncatedWithRealPrefixNotAnOom() {
        // ~4.4 MiB of DECOMPRESSED entry data (a few KB raw after deflate): over the
        // MAX_DECOMPRESSED_BYTES cap, so the read must stop at the cap with the real prefix.
        val xml =
            "<w:document><w:body>" +
                "<w:p><w:r><w:t>x</w:t></w:r></w:p>".repeat(130_000) +
                "</w:body></w:document>"
        val result = DocumentTextExtractor.extract(TextAttachmentKind.DOCX, docx(xml))
        assertTrue("a ~4.4 MiB document must hit the decompression cap", result is ExtractedText.Truncated)
        val truncated = result as ExtractedText.Truncated
        assertTrue("the bounded prefix must be real extracted text", truncated.text.contains("x"))
    }

    @Test
    fun anInflateBombPdfStreamIsBoundedTruncatedNotAnOom() {
        // A genuine zlib stream: a few KB raw that expands to 8 MiB — over the cap.
        val zeros = ByteArray(8 * 1024 * 1024)
        val deflater = Deflater()
        deflater.setInput(zeros)
        deflater.finish()
        val sink = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            sink.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        val raw = sink.toByteArray().toString(Charsets.ISO_8859_1)
        val result = DocumentTextExtractor.extract(TextAttachmentKind.PDF, pdf(raw).toByteArray(Charsets.ISO_8859_1))
        assertTrue("an 8 MiB inflate bomb must hit the decompression cap", result is ExtractedText.Truncated)
    }

    @Test
    fun htmlLongerThanTheCharCapIsTruncatedToExactlyTheCap() {
        val html = "<p>abc</p>".repeat(125_001) // ~500,002 extracted chars, over MAX_EXTRACTED_CHARS
        val result = DocumentTextExtractor.extract(TextAttachmentKind.HTML, html.toByteArray(Charsets.UTF_8))
        assertTrue("over-length text must hit the char cap", result is ExtractedText.Truncated)
        val truncated = result as ExtractedText.Truncated
        assertEquals(MAX_EXTRACTED_CHARS, truncated.text.length)
    }

    @Test
    fun aDocxWithoutReadableTextIsAnEmptySuccessNotUnreadable() {
        val xml = "<w:document><w:body><w:p><w:r><w:t></w:t></w:r></w:p></w:body></w:document>"
        assertEquals(
            ExtractedText.Success(""),
            DocumentTextExtractor.extract(TextAttachmentKind.DOCX, docx(xml)),
        )
    }

    @Test
    fun aPdfWithoutTextOperatorsIsAnEmptySuccessNotUnreadable() {
        val bytes = pdf("BT ET").toByteArray(Charsets.ISO_8859_1)
        assertEquals(
            ExtractedText.Success(""),
            DocumentTextExtractor.extract(TextAttachmentKind.PDF, bytes),
        )
    }
}
