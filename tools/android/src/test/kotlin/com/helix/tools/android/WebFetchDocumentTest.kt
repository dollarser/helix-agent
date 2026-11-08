package com.helix.tools.android

import com.helix.core.model.NoCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchDocumentTest {
    @Test
    fun extractsMainTextWithoutScriptsFormsOrNavigation() {
        val result =
            extract(
                """<html><head><title>Source title</title></head><body>
            <nav>navigation</nav><article><h1>Article</h1><p>First &amp; second</p><p>中文😀</p>
            <script>attack()</script><form>secret form</form><span hidden>hidden</span></article></body></html>""",
            )
        assertEquals("Source title", result.title)
        assertEquals("main-text", result.extraction)
        assertTrue(result.text.contains("First & second"))
        assertTrue(result.text.contains("中文😀"))
        assertFalse(result.text.contains("attack"))
        assertFalse(result.text.contains("navigation"))
        assertFalse(result.text.contains("secret"))
        assertFalse(result.text.contains("hidden"))
        assertEquals(64, result.contentHash.length)
    }

    @Test
    fun nonArticleHtmlHasExplicitBodyFallbackAndScriptOnlyPageIsEmpty() {
        assertEquals("body-text", extract("<p>Reference page</p>").extraction)
        assertEquals("", extract("<script>document.write('not executed')</script>").text)
    }

    @Test
    fun mainRegionIncludesMultipleArticlesAndEmptyRegionFallsBackToBody() {
        val result = extract("<main><article>First</article><article>Second</article></main>")
        assertTrue(result.text.contains("First"))
        assertTrue(result.text.contains("Second"))
        assertEquals("Fallback", extract("<main></main><p>Fallback</p>").text)
    }

    @Test
    fun mainSelectionExcludesOutsideArticlesAndRemovedHiddenRegions() {
        val result =
            extract(
                "<main hidden>Hidden main</main><article>Outside article</article>" +
                    "<div role=main><p>Selected main</p><nav>Navigation</nav></div><p>Outside body</p>",
            )
        assertEquals("main-text", result.extraction)
        assertEquals("Selected main", result.text)
        val fallback = extract("<main><script>ignored()</script></main><article>Visible article</article>")
        assertEquals("body-text", fallback.extraction)
        assertEquals("Visible article", fallback.text)
    }

    @Test
    fun htmlTruncationDetectsAstralCharacterAtBoundary() {
        val result = extract("<main>" + "a".repeat(WEB_TEXT_LIMIT) + "😀</main>")
        assertTrue(result.truncated)
        assertEquals(WEB_TEXT_LIMIT, result.text.length)
        val exact = extract("<main>" + "a".repeat(WEB_TEXT_LIMIT) + "</main>")
        assertFalse(exact.truncated)
    }

    @Test
    fun removedSubtreesStillCountTowardNodeBudget() {
        assertEquals(
            "html-node-limit",
            assertThrows(WebFetchFailure::class.java) {
                extract("<nav>" + "<i></i>".repeat(50_001) + "</nav><main>Small</main>")
            }.code,
        )
    }

    @Test
    fun declaredCharsetAndHtmlMetaAreHonored() {
        val bytes = "<meta charset=windows-1252><p>café</p>".toByteArray(charset("windows-1252"))
        val meta = WebFetchDocuments.extract(page(bytes), deadline(), NoCancellation)
        assertEquals("café", meta.text)
        val plain =
            WebFetchDocuments.extract(
                page("café".toByteArray(charset("windows-1252")), "text/plain", "windows-1252"),
                deadline(),
                NoCancellation,
            )
        assertEquals("café", plain.text)
    }

    @Test
    fun inputAndTextTruncationStayTruthfulAndDoNotSplitUnicode() {
        val text = "a".repeat(WEB_TEXT_LIMIT - 1) + "😀tail"
        val result = WebFetchDocuments.extract(page(text.toByteArray(), "text/plain"), deadline(), NoCancellation)
        assertTrue(result.truncated)
        assertEquals(WEB_TEXT_LIMIT - 1, result.text.length)
        val short =
            WebFetchDocuments.extract(
                page("ok".toByteArray()).copy(truncated = true),
                deadline(),
                NoCancellation,
            )
        assertTrue(short.truncated)
    }

    @Test
    fun malformedBytesAreMarkedAndUnsupportedCharsetFailsClearly() {
        val result =
            WebFetchDocuments.extract(
                page(byteArrayOf(0xc3.toByte(), 0x28), "text/plain"),
                deadline(),
                NoCancellation,
            )
        assertTrue(result.decodingLoss)
        assertEquals(
            "unsupported-charset",
            assertThrows(WebFetchFailure::class.java) {
                WebFetchDocuments.extract(
                    page(byteArrayOf(65), "text/plain", "not-a-charset"),
                    deadline(),
                    NoCancellation,
                )
            }.code,
        )
    }

    @Test
    fun nodeLimitAndDeadlineDoNotPretendToReadThePage() {
        assertEquals(
            "html-node-limit",
            assertThrows(WebFetchFailure::class.java) {
                extract("<i></i>".repeat(50_001))
            }.code,
        )
        assertEquals(
            "timed-out",
            assertThrows(WebFetchFailure::class.java) {
                WebFetchDocuments.extract(page("text".toByteArray()), 0, NoCancellation)
            }.code,
        )
    }

    private fun extract(html: String) = WebFetchDocuments.extract(page(html.toByteArray()), deadline(), NoCancellation)

    private fun deadline() = System.currentTimeMillis() + 10_000

    private fun page(
        bytes: ByteArray,
        type: String = "text/html",
        charset: String? = null,
    ) = WebFetchPage("https://example.com/source", 200, type, charset, bytes, false, 0)
}
