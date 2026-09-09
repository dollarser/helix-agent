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
}
