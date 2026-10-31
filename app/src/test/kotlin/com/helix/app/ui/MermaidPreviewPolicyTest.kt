package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidPreviewPolicyTest {
    @Test fun authorConfigurationAndInputBudgetsCannotChangeTheHost() {
        assertTrue(MermaidPreviewPolicy.valid("flowchart TD\n A-->B"))
        listOf("", "x".repeat(8193), "x\n".repeat(256), "x\u0000", "---\nconfig: {}\n---", "%%{init: {}}%%\n A-->B")
            .forEach { assertFalse(MermaidPreviewPolicy.valid(it)) }
        assertEquals(
            "mermaid/mermaid.tiny.js",
            MermaidPreviewPolicy.asset(MermaidPreviewPolicy.BASE + "mermaid.tiny.js"),
        )
        listOf(
            "https://example.invalid/mermaid.tiny.js",
            MermaidPreviewPolicy.BASE + "../math/katex.min.js",
            MermaidPreviewPolicy.BASE + "mermaid.tiny.js?query=1",
            "file:///private/secret",
        ).forEach { assertNull(MermaidPreviewPolicy.asset(it)) }
    }

    @Test fun labelsAreDataAndSvgGetsIndependentActiveContentRestrictions() {
        val source = "flowchart TD\n A[\"</script><img src=x>\"]"
        val html = MermaidPreviewPolicy.html(source, 0, 0xffffff, 16f, "syntheticnonce1234")
        assertFalse(html.contains(source))
        assertTrue(html.contains("\\u003c/script\\u003e"))
        assertTrue(html.contains("securityLevel:'strict'"))
        assertTrue(html.contains("maxEdges:128"))
        assertTrue(html.contains("frame-src 'none'"))
        assertTrue(html.contains("node.replaceWith"))
        assertFalse(html.contains("result.bindFunctions"))
        assertThrows(IllegalArgumentException::class.java) {
            MermaidPreviewPolicy.html("%%{init:{}}%%", 0, 0, 16f, "syntheticnonce1234")
        }
    }
}
