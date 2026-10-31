package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDiagramPolicyTest {
    @Test fun onlyClosedExplicitDiagramFencesBecomeInlineCandidates() {
        val open = markdownBlocks("```mermaid\nflowchart TD\n A-->B").single()
        assertFalse(open.complete)
        val prefix = markdownBlocks("```mermaid\n A-->B\n```still-streaming").single()
        assertFalse(prefix.complete)
        assertTrue(prefix.text.endsWith("```still-streaming"))
        val closed = markdownBlocks("```mermaid\nflowchart TD\n A-->B\n```").single()
        assertTrue(closed.complete)
        assertEquals(BlockPreviewKind.MERMAID, inlineDiagramKind(closed.language))
        assertEquals(BlockPreviewKind.SVG, inlineDiagramKind("SVG"))
        assertEquals(BlockPreviewKind.SVG, inlineDiagramKind("image/svg+xml"))
        assertNull(inlineDiagramKind("html"))
        assertNull(inlineDiagramKind("xml"))
        assertNull(inlineDiagramKind("math"))
    }

    @Test fun cachedPixelsCannotCrossSourceThemeFontWidthOrType() {
        val first = InlineDiagramInput("synthetic", BlockPreviewKind.SVG, 0, 1, 1f, 300, 3f)
        val alternatives =
            listOf(
                first.copy(source = "changed"),
                first.copy(kind = BlockPreviewKind.MERMAID),
                first.copy(background = 2),
                first.copy(foreground = 3),
                first.copy(font = 1.8f),
                first.copy(width = 200),
                first.copy(density = 2f),
            )
        assertEquals(first.key, first.copy().key)
        alternatives.forEach { assertNotEquals(first.key, it.key) }
        assertFalse(first.key.contains("synthetic"))
    }

    @Test fun svgInputIsBoundedDataAndOnlyFullExplicitFilesQualify() {
        val text = "<svg width=\"30\" height=\"20\"><rect width=\"10\" height=\"10\"/></svg>"
        val info =
            com.helix.app.files.FileManagerService.FileMeta(
                text.toByteArray().size.toLong(),
                0,
                "image/svg+xml",
                true,
                null,
                true,
            )
        assertTrue(svgFilePreview("test.svg", info, text))
        assertFalse(svgFilePreview("test.svg", info.copy(sizeBytes = info.sizeBytes + 1), text))
        assertFalse(svgFilePreview("test.svg", info.copy(sizeBytes = -1), text))
        assertFalse(svgFilePreview("test.html", info.copy(mimeType = "text/html"), text))
        assertFalse(SvgPreviewPolicy.valid("<!DOCTYPE svg SYSTEM 'https://example.invalid/x'>" + text))
        assertFalse(SvgPreviewPolicy.valid("<!ENTITY x 'x'>" + text))
        assertFalse(SvgPreviewPolicy.valid("x".repeat(65_537)))
        val html = SvgPreviewPolicy.html(text, 0, 1, 16f, "syntheticnonce1234")
        assertFalse(html.contains(text))
        assertTrue(html.contains("purify.min.js"))
        assertTrue(html.contains("sanitizeDiagramSvg"))
        assertNull(SvgPreviewPolicy.asset(SvgPreviewPolicy.BASE + "purify.min.js?x=1"))
    }
}
