package com.helix.tools.browser

import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSnapshotLinksTest {
    @Test
    fun retainsObservedRelativeHrefAndOriginalTokenWithoutGuessingDestination() {
        val node = node(href = "/goto?url=opaque", text = "example.org › domains")
        val (links, truncated) = BrowserSnapshotLinks.project(outcome(listOf(node)))
        assertFalse(truncated)
        assertEquals(1, links.size)
        val link = links.single().jsonObject
        assertEquals(node.text, link.getValue("text").jsonPrimitive.content)
        assertEquals(node.href, link.getValue("href").jsonPrimitive.content)
        assertEquals(node.token, link.getValue("token").jsonPrimitive.content)
        assertEquals("false", link.getValue("hrefMayBeTruncated").jsonPrimitive.content)
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(BrowserSnapshotLinks.schema(), links))
    }

    @Test
    fun distinguishesNoObservedLinksFromTruncatedTree() {
        val empty = outcome(listOf(node(href = "")))
        assertEquals(0, BrowserSnapshotLinks.project(empty).first.size)
        assertFalse(BrowserSnapshotLinks.project(empty).second)
        assertTrue(BrowserSnapshotLinks.project(empty.copy(truncated = true)).second)
    }

    @Test
    fun boundsWholeEntriesByCountAndSerializedUtf8Size() {
        val many = outcome(List(BrowserSnapshotLinks.MAX_LINKS + 1) { node() })
        val countBound = BrowserSnapshotLinks.project(many)
        assertEquals(BrowserSnapshotLinks.MAX_LINKS, countBound.first.size)
        assertTrue(countBound.second)
        val largeNode =
            node(
                href = "中".repeat(MAX_NODE_TEXT),
                text = "\\\"".repeat(100),
            ).copy(token = "t".repeat(MAX_TOKEN))
        val sizeBound = BrowserSnapshotLinks.project(outcome(List(BrowserSnapshotLinks.MAX_LINKS) { largeNode }))
        assertTrue(sizeBound.second)
        assertTrue(sizeBound.first.size < BrowserSnapshotLinks.MAX_LINKS)
        assertTrue(
            sizeBound.first
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size <= BrowserSnapshotLinks.MAX_JSON_BYTES,
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(BrowserSnapshotLinks.schema(), sizeBound.first),
        )
        assertEquals(
            largeNode.token,
            sizeBound.first
                .last()
                .jsonObject
                .getValue("token")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun marksSourceLimitAsPossiblyTruncatedAndAvoidsBrokenUnicodePrefix() {
        val href = "x".repeat(MAX_NODE_TEXT - 1) + "😀tail"
        val (links, _) = BrowserSnapshotLinks.project(outcome(listOf(node(href = href))))
        val link = links.single().jsonObject
        assertEquals("x".repeat(MAX_NODE_TEXT - 1), link.getValue("href").jsonPrimitive.content)
        assertEquals("true", link.getValue("hrefMayBeTruncated").jsonPrimitive.content)
        val (atLimit, _) = BrowserSnapshotLinks.project(outcome(listOf(node(href = "x".repeat(MAX_NODE_TEXT)))))
        assertEquals(
            "true",
            atLimit
                .single()
                .jsonObject
                .getValue("hrefMayBeTruncated")
                .jsonPrimitive.content,
        )
    }

    private fun node(
        href: String = "/actual-target",
        text: String = "Link",
    ): BrowserNodeView = BrowserNodeView(0, "link", text, "", href, "", "original-token")

    private fun outcome(nodes: List<BrowserNodeView>): SnapshotOutcome =
        SnapshotOutcome(
            true,
            "tab",
            "https://example.com/",
            "Title",
            "https://example.com",
            1,
            "fp",
            false,
            nodes.size,
            nodes,
            "",
        )
}
