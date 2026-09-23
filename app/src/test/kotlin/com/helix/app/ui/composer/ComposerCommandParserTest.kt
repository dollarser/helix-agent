package com.helix.app.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerCommandParserTest {
    @Test
    fun `parseQuery detects slash command at start of input`() {
        val query = ComposerCommandParser.parseQuery("/pla", 4)
        assertNotNull(query)
        assertEquals('/', query!!.triggerChar)
        assertEquals("pla", query.token)
        assertEquals(0, query.rangeStart)
        assertEquals(4, query.rangeEnd)
    }

    @Test
    fun `parseQuery ignores slash in middle of word or URL`() {
        assertNull(ComposerCommandParser.parseQuery("http://example", 14))
        assertNull(ComposerCommandParser.parseQuery("foo/bar", 7))
    }

    @Test
    fun `parseQuery detects slash after newline`() {
        val text = "first line\n/goal"
        val query = ComposerCommandParser.parseQuery(text, text.length)
        assertNotNull(query)
        assertEquals('/', query!!.triggerChar)
        assertEquals("goal", query.token)
        assertEquals(11, query.rangeStart)
    }

    @Test
    fun `parseQuery detects at mention after whitespace`() {
        val text = "Please inspect @REA"
        val query = ComposerCommandParser.parseQuery(text, text.length)
        assertNotNull(query)
        assertEquals('@', query!!.triggerChar)
        assertEquals("REA", query.token)
    }

    @Test
    fun `parseQuery ignores email address at symbol`() {
        assertNull(ComposerCommandParser.parseQuery("user@example.com", 16))
    }

    @Test
    fun `filterSuggestions matches slash commands case insensitively`() {
        val query = AutocompleteQuery('/', "PL", 0, 3)
        val results = ComposerCommandParser.filterSuggestions(query)
        assertEquals(1, results.size)
        assertEquals("/plan", results.first().label)
        assertEquals(ComposerSuggestionType.SLASH_COMMAND, results.first().type)
    }

    @Test
    fun `filterSuggestions matches connectors and files on at mention`() {
        val connectors =
            listOf(
                ComposerSuggestionItem(
                    id = "c1",
                    label = "GitLab Connector",
                    detail = "Manage issues",
                    type = ComposerSuggestionType.CONNECTOR,
                    insertText = "@connector:gitlab ",
                ),
            )
        val files =
            listOf(
                ComposerSuggestionItem(
                    id = "f1",
                    label = "README.md",
                    detail = "docs",
                    type = ComposerSuggestionType.FILE,
                    insertText = "@file:README.md ",
                ),
            )

        val queryAll = AutocompleteQuery('@', "", 0, 1)
        val allResults = ComposerCommandParser.filterSuggestions(queryAll, connectors = connectors, files = files)
        assertEquals(2, allResults.size)

        val queryGit = AutocompleteQuery('@', "git", 0, 4)
        val gitResults = ComposerCommandParser.filterSuggestions(queryGit, connectors = connectors, files = files)
        assertEquals(1, gitResults.size)
        assertEquals("GitLab Connector", gitResults.first().label)
    }

    @Test
    fun `applySuggestion replaces query token and calculates new cursor position`() {
        val text = "Check out @read"
        val query = AutocompleteQuery('@', "read", 10, 15)
        val suggestion =
            ComposerSuggestionItem(
                id = "f1",
                label = "README.md",
                type = ComposerSuggestionType.FILE,
                insertText = "@README.md ",
            )
        val (newText, newCursor) = ComposerCommandParser.applySuggestion(text, query, suggestion)
        assertEquals("Check out @README.md ", newText)
        assertEquals(21, newCursor)
    }
}
