package com.helix.tools.android

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolContractTest {
    @Test
    fun toolSelectionDistinguishesRawTextPageExtractionAndSearch() {
        val raw = HttpFetchTool.descriptor().description
        val page = WebFetchTool.descriptor().description
        val search = WebSearchTool.descriptor().description
        assertTrue(raw.contains("web_fetch") && raw.contains("browser_open"))
        assertTrue(page.contains("http_fetch") && page.contains("without JavaScript"))
        assertTrue(search.contains("browser_search") && search.contains("snippets"))
        assertTrue(search.contains("uncertain search"))
    }

    @Test
    fun rawFetchDoesNotPromiseACompleteResourceLength() {
        val fields =
            HttpFetchTool
                .descriptor()
                .outputSchema
                .getValue("properties")
                .jsonObject
        val length =
            fields
                .getValue("byteLength")
                .jsonObject
                .getValue("description")
                .jsonPrimitive.content
        assertTrue(length.contains("observed"))
        assertTrue(length.contains("not always"))
        assertFalse(length.contains("the full length"))
    }
}
