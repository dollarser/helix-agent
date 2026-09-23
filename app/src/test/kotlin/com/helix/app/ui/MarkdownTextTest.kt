package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {
    @Test fun blocksRecognizeHeadingsListsQuotesCodeAndTables() {
        val source = "# Title\n\n- item\n> quote\n```kotlin\nval x = 1\n```\n| A | B |\n| --- | --- |\n| 1 | 2 |"
        assertEquals(
            listOf("HEADING", "LIST", "QUOTE", "CODE", "TABLE"),
            markdownBlocks(source).map { it.kind.name },
        )
    }

    @Test fun incompleteStreamingCodeFencePreservesTheCode() {
        assertEquals("a < b\n**literal**", markdownBlocks("```\na < b\n**literal**").single().text)
        assertEquals("unfinished **bold", markdownInline("unfinished **bold").text)
    }

    @Test fun inlineFormattingPreservesWordsAndRestrictsLinks() {
        val result = markdownInline("**bold** *italic* `code` [site](https://example.com)")
        assertEquals("bold italic code site", result.text)
        assertTrue(result.spanStyles.isNotEmpty())
        assertEquals("[bad](javascript:evil)", markdownInline("[bad](javascript:evil)").text)
        assertEquals("<script>literal</script>", markdownInline("<script>literal</script>").text)
        assertEquals("*literal*", markdownInline("\\*literal\\*").text)
    }

    @Test fun codeFenceExtractsLanguageTagCorrectly() {
        val blocks = markdownBlocks("```kotlin\nval a = 1\n```\n```python\nb = 2\n```\n```\nc = 3\n```")
        val codeBlocks = blocks.filter { it.kind == MarkdownBlock.Kind.CODE }
        assertEquals(3, codeBlocks.size)
        assertEquals("kotlin", codeBlocks[0].language)
        assertEquals("python", codeBlocks[1].language)
        assertEquals(null, codeBlocks[2].language)
    }

    @Test fun highlightAnnotatedStringHighlightsMatchesCaseInsensitively() {
        val style =
            androidx.compose.ui.text
                .SpanStyle(color = androidx.compose.ui.graphics.Color.Yellow)
        val source =
            androidx.compose.ui.text
                .AnnotatedString("Hello world, hello Helix")
        val result = highlightAnnotatedString(source, "hello", style)

        assertEquals("Hello world, hello Helix", result.text)
        val matches = result.spanStyles.filter { it.item == style }
        assertEquals(2, matches.size)
        assertEquals(0, matches[0].start)
        assertEquals(5, matches[0].end)
        assertEquals(13, matches[1].start)
        assertEquals(18, matches[1].end)
    }

    @Test fun highlightAnnotatedStringPreservesExistingStyles() {
        val style =
            androidx.compose.ui.text
                .SpanStyle(color = androidx.compose.ui.graphics.Color.Yellow)
        val source = markdownInline("**bold** target")
        val result = highlightAnnotatedString(source, "target", style)

        assertEquals("bold target", result.text)
        val match = result.spanStyles.first { it.item == style }
        assertEquals(5, match.start)
        assertEquals(11, match.end)
        assertTrue(result.spanStyles.any { it.item != style })
    }

    @Test fun highlightAnnotatedStringWithBlankQueryReturnsOriginal() {
        val style =
            androidx.compose.ui.text
                .SpanStyle(color = androidx.compose.ui.graphics.Color.Yellow)
        val source =
            androidx.compose.ui.text
                .AnnotatedString("no changes")
        val resultEmpty = highlightAnnotatedString(source, "", style)
        val resultBlank = highlightAnnotatedString(source, "   ", style)
        assertEquals(source, resultEmpty)
        assertEquals(source, resultBlank)
    }
}
