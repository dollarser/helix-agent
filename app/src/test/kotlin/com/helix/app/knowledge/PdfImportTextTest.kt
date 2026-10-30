package com.helix.app.knowledge

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImportTextTest {
    @Test fun cappedPreviewPreservesUnicodeAndReportsOnlyActualCoverage() {
        val collector = PdfImportText(120)
        collector.append(
            1,
            buildJsonObject {
                put("text", "😀".repeat(100))
                put("truncated", false)
            },
        )
        val result = collector.result(3, null)
        assertTrue(result.text.length <= 120)
        assertFalse(result.text.last().isHighSurrogate())
        assertEquals(
            1,
            result.coverage
                .getValue("processedPages")
                .jsonPrimitive.int,
        )
        assertTrue(
            result.coverage
                .getValue("truncated")
                .jsonPrimitive.boolean,
        )
        val page =
            result.coverage
                .getValue("pages")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("partial", page.getValue("state").jsonPrimitive.content)
        assertEquals(result.text.length, page.getValue("end").jsonPrimitive.int)
    }

    @Test fun missingTextAndInterruptedPagesNeverClaimAnEmptyOrCompleteDocument() {
        val collector = PdfImportText()
        collector.append(
            1,
            buildJsonObject {
                put("text", "")
                put("truncated", false)
            },
        )
        val result = collector.result(2, "TIMED_OUT")
        assertEquals(
            "TIMED_OUT",
            result.coverage
                .getValue("reason")
                .jsonPrimitive.content,
        )
        assertEquals(
            1,
            result.coverage
                .getValue("processedPages")
                .jsonPrimitive.int,
        )
        assertEquals(
            "no_text",
            result.coverage
                .getValue("pages")
                .jsonArray
                .single()
                .jsonObject
                .getValue("state")
                .jsonPrimitive.content,
        )
        assertTrue(
            result.coverage
                .getValue("truncated")
                .jsonPrimitive.boolean,
        )
    }
}
