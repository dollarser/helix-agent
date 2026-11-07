package com.helix.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaPreviewPolicyTest {
    @Test fun onlyCompleteStandaloneMathBlocksBecomePreviews() {
        val blocks = markdownBlocks("before\n$$\n\\frac{1}{2}\n$$\nafter\n\\[x^2\\]\n```math\nx=1\n```")
        assertEquals(listOf("before", "\\frac{1}{2}", "after", "x^2", "x=1"), blocks.map { it.text })
        assertEquals(listOf(null, "math", null, "math", "math"), blocks.map { it.language })
        assertEquals("cost $5 and $6", markdownBlocks("cost $5 and $6").single().text)
        assertEquals("\$x\$", markdownBlocks("\$x\$").single().text)
    }

    @Test fun streamingAndCodeExamplesRetainTheirOriginalContent() {
        assertEquals(
            listOf("$$", "unfinished", "# heading"),
            markdownBlocks("$$\nunfinished\n# heading").map {
                if (it.kind ==
                    MarkdownBlock.Kind.HEADING
                ) {
                    "# ${it.text}"
                } else {
                    it.text
                }
            },
        )
        val code = markdownBlocks("```text\n$$\nnot a formula\n$$\n```").single()
        assertEquals("text", code.language)
        assertEquals("$$\nnot a formula\n$$", code.text)
        assertEquals("latex", markdownBlocks("```latex\n\\frac{1}{2}\n```").single().language)
    }

    @Test fun untrustedFormulaIsJsonDataAndCannotTerminateTheTrustedScript() {
        val attack = "</script><img src=https://example.invalid/>\u2028"
        val html = FormulaPreviewPolicy.html(attack, 0, 0xffffff, 16f, "syntheticnonce1234")
        assertFalse(html.contains(attack))
        assertTrue(html.contains("\\u003c/script\\u003e"))
        assertTrue(html.contains("\\u2028"))
        assertTrue(html.contains("trust:false"))
        assertTrue(html.contains("maxExpand:256"))
        assertTrue(html.contains("connect-src 'none'"))
        assertThrows(IllegalArgumentException::class.java) {
            FormulaPreviewPolicy.html("x".repeat(8193), 0, 0, 16f, "syntheticnonce1234")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FormulaPreviewPolicy.html("x\u0000", 0, 0, 16f, "syntheticnonce1234")
        }
    }

    @Test fun resourceRequestsCannotReachOtherAssetsFilesOrTheNetwork() {
        val base = FormulaPreviewPolicy.BASE
        assertEquals("math/katex.min.js", FormulaPreviewPolicy.asset(base + "katex.min.js"))
        assertEquals(
            "math/fonts/KaTeX_Size1-Regular.woff2",
            FormulaPreviewPolicy.asset(base + "fonts/KaTeX_Size1-Regular.woff2"),
        )
        assertEquals(
            "math/fonts/KaTeX_Main-Regular.woff2",
            FormulaPreviewPolicy.asset(base + "fonts/KaTeX_Main-Regular.woff2"),
        )
        listOf(
            "file:///data/data/secret",
            "content://documents/secret",
            "https://example.invalid/katex.min.js",
            base + "../licenses/KaTeX-MIT.txt",
            base + "%2e%2e/katex.min.js",
            base + "katex.min.js?key=synthetic",
            base + "fonts/../../private",
            "http://appassets.androidplatform.net/assets/math/katex.min.js",
        ).forEach {
            assertNull(it, FormulaPreviewPolicy.asset(it))
        }
    }
}
