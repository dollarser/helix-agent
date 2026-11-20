package com.helix.app.chat

import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionToolDescriptorsTest {
    @Test fun everyInputParameterExplainsItsSourceAndUnits() {
        for (name in SessionToolDescriptors.names) {
            val descriptor = SessionToolDescriptors.descriptor(name)
            for ((field, property) in descriptor.inputSchema.getValue("properties").jsonObject) {
                val help = property.jsonObject["description"]?.jsonPrimitive?.content
                assertTrue("$name.$field", !help.isNullOrBlank())
            }
            assertTrue(
                descriptor.outputSchema
                    .getValue("properties")
                    .jsonObject
                    .isNotEmpty(),
            )
        }
    }

    @Test fun readOutputMatchesActualUnicodePageAndRejectsMissingEvidence() {
        val page = ToolResultReadTool.page("ignored", "a😀b", 1, maxCharacters = 1)
        val result =
            kotlinx.serialization.json.JsonObject(
                page - "resultRef" +
                    mapOf("messageId" to JsonPrimitive("message"), "contentHash" to JsonPrimitive("hash")),
            )
        val schema = SessionToolDescriptors.descriptor("session_read").outputSchema
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(schema, result))
        assertTrue(
            ToolSchemaValidator.validate(schema, kotlinx.serialization.json.JsonObject(result - "content"))
                is ToolSchemaValidation.Invalid,
        )
    }

    @Test fun cursorGuidancePreservesOptionalNonNullableCursorContract() {
        for (name in listOf("session_list", "session_history", "session_search")) {
            val schema = SessionToolDescriptors.descriptor(name).inputSchema
            val cursor =
                schema
                    .getValue("properties")
                    .jsonObject
                    .getValue("cursor")
                    .jsonObject
            val help = cursor.getValue("description").jsonPrimitive.content
            assertTrue(help.contains("Omit for the first page"))
            assertTrue(help.contains("exact cursor"))
            assertTrue(help.contains("never invent or modify"))
            assertFalse(schema.getValue("required").jsonArray.contains(JsonPrimitive("cursor")))
            assertEquals(JsonPrimitive("string"), cursor["type"])
            val firstPage = if (name == "session_search") """{"query":"needle"}""" else "{}"
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(schema, Json.parseToJsonElement(firstPage)),
            )
            for (value in listOf(JsonNull, JsonPrimitive(""))) {
                val invalid =
                    buildJsonObject {
                        if (name == "session_search") put("query", JsonPrimitive("needle"))
                        put("cursor", value)
                    }
                assertTrue(ToolSchemaValidator.validate(schema, invalid) is ToolSchemaValidation.Invalid)
            }
        }
    }

    @Test fun historyAndSearchExplainTheTwoDistinctPaginationProtocols() {
        for (name in listOf("session_history", "session_search")) {
            val descriptor = SessionToolDescriptors.descriptor(name)
            val properties = descriptor.inputSchema.getValue("properties").jsonObject
            val cursorHelp =
                properties
                    .getValue("cursor")
                    .jsonObject
                    .getValue("description")
                    .jsonPrimitive.content
            val beforeHelp =
                properties
                    .getValue("beforeSequence")
                    .jsonObject
                    .getValue("description")
                    .jsonPrimitive.content
            assertTrue(cursorHelp.contains("Only with targetSessionId"))
            assertTrue(cursorHelp.contains("same target and query"))
            assertTrue(cursorHelp.contains("Do not combine with beforeSequence"))
            assertTrue(beforeHelp.contains("Only without targetSessionId"))
            assertTrue(beforeHelp.contains("nextBeforeSequence"))
            assertTrue(beforeHelp.contains("Do not combine with cursor"))
            assertTrue(descriptor.description.contains("without targetSessionId"))
        }
        val listHelp =
            SessionToolDescriptors
                .descriptor("session_list")
                .inputSchema
                .getValue("properties")
                .jsonObject
                .getValue("cursor")
                .jsonObject
                .getValue("description")
                .jsonPrimitive.content
        assertFalse(listHelp.contains("targetSessionId"))
    }
}
