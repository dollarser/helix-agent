package com.helix.provider.openai.responses

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeWebSearchRequestTest {
    private val request = ModelRequest("owner-model", listOf(ModelMessage(ModelRole.USER, "Research")))
    private val resolver = ImageResolver { error("No images") }

    @Test fun defaultsOffAndPreservesSelectedModel() {
        val encoder = ResponsesRequestEncoder(resolver, true)
        val off = Json.parseToJsonElement(encoder.encode(request)).jsonObject
        assertFalse(off.containsKey("tools"))
        val on = Json.parseToJsonElement(encoder.encode(request.copy(nativeWebSearch = true))).jsonObject
        assertEquals("owner-model", on["model"]!!.jsonPrimitive.content)
        assertEquals(
            "web_search",
            on["tools"]!!
                .jsonArray
                .single()
                .jsonObject["type"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun compatibilityAndDuplicateFunctionsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            ResponsesRequestEncoder(resolver).encode(request.copy(nativeWebSearch = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResponsesRequestEncoder(resolver, true).encode(
                request.copy(
                    nativeWebSearch = true,
                    tools = listOf(ModelToolSchema(ToolName("web_search"), "search", """{"type":"object"}""")),
                ),
            )
        }
    }
}
