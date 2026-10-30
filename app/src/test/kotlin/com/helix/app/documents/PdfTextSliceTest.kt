package com.helix.app.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextSliceTest {
    @Test fun pagesReassembleExactTextWithoutSplittingUnicodeOrDroppingSeparators() {
        val chunks = listOf("A😀中文", "B", "尾部答案")
        val expected = chunks.joinToString("\n", postfix = "\n")
        val rebuilt = StringBuilder()
        var offset = 0
        do {
            val result = pdfTextSlice(chunks.asSequence(), offset, 3)
            rebuilt.append(result.text)
            assertTrue(result.end > offset)
            offset = result.end
        } while (result.truncated)
        assertEquals(expected, rebuilt.toString())
        assertFalse(pdfTextSlice(chunks.asSequence(), offset, 3).truncated)
    }

    @Test fun emptyAndInvalidRangesAreNotFabricatedContent() {
        assertEquals("", pdfTextSlice(emptySequence(), 0, 10).text)
        assertEquals(
            "RANGE_OUT_OF_BOUNDS",
            assertThrows(
                com.helix.extensions.documents.DocumentException::class.java,
            ) { pdfTextSlice(sequenceOf("a"), 3, 10) }.code,
        )
        assertThrows(IllegalArgumentException::class.java) { pdfTextSlice(sequenceOf("a"), 0, 0) }
    }
}
