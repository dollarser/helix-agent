package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMathTest {
    @Test
    fun completeMathOnlyWithBoundedCurrencyAndCodeHandling() {
        val text = "Energy \\(E=mc^2\\) and \$x+y\$; cost \$5 and \$10; `\$ignored\$`."
        assertEquals(listOf("E=mc^2", "x+y"), inlineMath(text).map { it.source })
        assertTrue(inlineMath("unfinished \\(x+1 and \$x").isEmpty())
        assertTrue(inlineMath("literal \$\$x\$\$ and `\\(code\\)`").isEmpty())
        assertTrue(inlineMath("escaped \\\u0024x\$").isEmpty())
        assertEquals(8, inlineMath(("\\(x\\) ").repeat(100)).size)
        assertTrue(inlineMath("a".repeat(65537) + "\\(x\\)").isEmpty())
        assertEquals(listOf("y"), inlineMath("[\\(x\\)](https://example.invalid) then \\(y\\)").map { it.source })
        assertTrue(inlineMath("[".repeat(65536)).isEmpty())
        assertTrue(inlineMath("\\(".repeat(30000)).isEmpty())
    }

    @Test
    fun inlineHtmlUsesExistingRestrictedKatexAndNoSharedMacros() {
        val page =
            FormulaPreviewPolicy.html(
                "\\href{https://evil.invalid}{x}",
                0,
                0,
                16f,
                "0123456789abcdef",
                inline = true,
            )
        assertTrue(page.contains("displayMode:false,trust:false"))
        assertTrue(page.contains("connect-src 'none'"))
        assertTrue(page.contains("padding:0px"))
        assertTrue(page.contains("background:transparent"))
        assertFalse(page.contains("macros:"))
    }
}
