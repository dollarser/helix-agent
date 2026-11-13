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
    @Test fun cursorGuidancePreservesOptionalNonNullableCursorContract() {
        for (name in listOf("session.list", "session.history", "session.search")) {
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
            val firstPage = if (name == "session.search") """{"query":"needle"}""" else "{}"
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(schema, Json.parseToJsonElement(firstPage)),
            )
            for (value in listOf(JsonNull, JsonPrimitive(""))) {
                val invalid =
                    buildJsonObject {
                        if (name == "session.search") put("query", JsonPrimitive("needle"))
                        put("cursor", value)
                    }
                assertTrue(ToolSchemaValidator.validate(schema, invalid) is ToolSchemaValidation.Invalid)
            }
        }
    }

    @Test fun historyAndSearchExplainTheTwoDistinctPaginationProtocols() {
        for (name in listOf("session.history", "session.search")) {
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
                .descriptor("session.list")
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
