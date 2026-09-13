package com.helix.feature.files

import com.helix.core.model.TextAttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P0-B document batch (doc PX-05): [DocumentTextExtractor] — the zero-dependency, fail-closed
 * on-device text extraction for PDF / DOCX / HTML. The fixtures are REAL in-memory containers (a
 * genuine zip for DOCX, a hand-written minimal PDF), not mocks: the extractor's whole job is to
 * read the actual bytes. Malformed inputs must yield "" (fail-closed), never throw.
 */
class DocumentTextExtractorTest {
    // ── HTML ────────────────────────────────────────────────────────────────────

    @Test
    fun htmlStripsTagsScriptStyleAndDecodesEntities() {
        val html =
            "<!DOCTYPE html><html><head><title>t</title>" +
                "<style>body{color:red}</style><script>var x=1;</script></head>" +
                "<body><h1>Report</h1><p>First &amp; second.</p>" +
                "<ul><li>one</li><li>two</li></ul>" +
                "<p>Broken<br/>line</p><!-- a comment --><p>End</p></body></html>"
        val text = DocumentTextExtractor.extract(TextAttachmentKind.HTML, html.toByteArray(Charsets.UTF_8))
        assertEquals("Report", text.lineSequence().first())
        assertTrue("script/style must be dropped", !text.contains("var x") && !text.contains("color:red"))
        assertTrue("a comment must be dropped", !text.contains("a comment"))
        assertTrue("the &amp; entity must decode", text.contains("First & second."))
        assertTrue("list items must be present", text.contains("one") && text.contains("two"))
        assertTrue("a <br/> must become a newline", text.contains("Broken\nline"))
        assertTrue("a <p> must become a newline", text.contains("End"))
    }

    @Test
    fun htmlIsBoundedAndClean() {
        val html = "<html><body><p>a</p><p></p><p></p><p>b</p></body></html>"
        val text = DocumentTextExtractor.extract(TextAttachmentKind.HTML, html.toByteArray(Charsets.UTF_8))
        // Three blank lines from the empty <p>s collapse to one; no leading/trailing whitespace.
        assertEquals("a\n\nb", text)
    }

    // ── DOCX (a real zip) ───────────────────────────────────────────────────────

    private fun docx(documentXml: String): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            out.toByteArray()
        }

    @Test
    fun docxExtractsParagraphTextFromTheRealZipEntry() {
        val xml =
            "<w:document><w:body>" +
                "<w:p><w:r><w:t>Para one</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>Para two</w:t></w:r></w:p>" +
                "</w:body></w:document>"
        val text = DocumentTextExtractor.extract(TextAttachmentKind.DOCX, docx(xml))
        assertEquals("Para one\nPara two", text)
    }

    @Test
    fun docxTurnsTabsAndBreaksIntoWhitespace() {
        val xml =
            "<w:p><w:r><w:t>A</w:t><w:tab/></w:r><w:r><w:t>B</w:t><w:br/></w:r></w:p>"
        val text = DocumentTextExtractor.extract(TextAttachmentKind.DOCX, docx(xml))
        assertTrue("a <w:tab/> must become a tab", text.contains("A\tB"))
    }

    @Test
    fun aZipWithoutADocumentXmlYieldsEmptyText() {
        val noDoc =
            ByteArrayOutputStream().use { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("other.txt"))
                    zip.write("hi".toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                out.toByteArray()
            }
        assertEquals("", DocumentTextExtractor.extract(TextAttachmentKind.DOCX, noDoc))
    }

    // ── PDF (a hand-written minimal document) ───────────────────────────────────

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
    fun pdfReadsTheTextShowingOperator() {
        val bytes = pdf("BT /F1 12 Tf (Hello World) Tj ET").toByteArray(Charsets.ISO_8859_1)
        val text = DocumentTextExtractor.extract(TextAttachmentKind.PDF, bytes)
        assertEquals("Hello World", text)
    }

    @Test
    fun pdfInsertsNewlinesAtPositioningOperators() {
        val bytes = pdf("BT /F1 12 Tf (Line one) Tj 0 -14 Td (Line two) Tj ET").toByteArray(Charsets.ISO_8859_1)
        val text = DocumentTextExtractor.extract(TextAttachmentKind.PDF, bytes)
        assertEquals("Line one\nLine two", text)
    }

    @Test
    fun pdfDecodesSimpleEscapesInsideStrings() {
        val bytes = pdf("BT /F1 12 Tf (line1\\nline2) Tj ET").toByteArray(Charsets.ISO_8859_1)
        val text = DocumentTextExtractor.extract(TextAttachmentKind.PDF, bytes)
        assertEquals("line1\nline2", text)
    }

    // ── Fail-closed: malformed / out-of-kind inputs never throw, yield "" ──────

    @Test
    fun malformedOrOutOfKindInputYieldsEmptyTextWithoutThrowing() {
        assertEquals("", DocumentTextExtractor.extract(TextAttachmentKind.PDF, "not a pdf".toByteArray(Charsets.UTF_8)))
        assertEquals(
            "",
            DocumentTextExtractor.extract(TextAttachmentKind.DOCX, "not a zip".toByteArray(Charsets.UTF_8)),
        )
        assertEquals("", DocumentTextExtractor.extract(TextAttachmentKind.HTML, ByteArray(0)))
        // A non-extracted kind (raw first-batch text) is out of scope for the extractor.
        assertEquals("", DocumentTextExtractor.extract(TextAttachmentKind.TXT, "plain".toByteArray(Charsets.UTF_8)))
    }
}
