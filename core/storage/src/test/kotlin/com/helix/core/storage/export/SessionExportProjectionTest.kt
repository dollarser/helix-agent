package com.helix.core.storage.export

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionExportProjectionTest {
    private val projection = SessionExportProjection { text, _ -> text.replace("synthetic-secret", "[redacted]") }

    @Test fun providerConfigurationAndHistoricalUnknownsCannotLeakOrBecomeInventedFacts() {
        val row =
            buildJsonObject {
                put("omittedFields", buildJsonObject {})
                put(
                    "data",
                    buildJsonObject {
                        put("id", "model")
                        put(
                            "providerSnapshot",
                            buildJsonObject {
                                put("displayName", "fixture")
                                put("model", "test-model")
                                put("endpoint", "https://synthetic-secret.invalid")
                            }.toString(),
                        )
                        put("usage", """{"inputTokens":12,"outputTokens":null}""")
                    },
                )
            }
        val result = projection.project(SessionExportType.MODEL_CALL, row)
        assertFalse(result.toString().contains("synthetic-secret"))
        assertFalse(result.containsKey("providerSnapshot"))
        assertEquals(
            "12",
            result
                .getValue("usage")
                .jsonObject
                .getValue("inputTokens")
                .toString(),
        )
        assertEquals(JsonNull, result.getValue("usage").jsonObject["outputTokens"])
        assertEquals(
            "not_persisted",
            result
                .getValue("finishReason")
                .jsonObject
                .getValue("reason")
                .jsonPrimitive.content,
        )
    }

    @Test fun oversizedAndTransformedFieldsAreExplicit() {
        assertEquals(
            "redacted",
            projection
                .text("synthetic-secret")
                .getValue("availability")
                .jsonPrimitive.content,
        )
        val large = projection.text("x".repeat(SessionExportFormat.INLINE_BYTES + 1))
        assertEquals("omitted_limit", large.getValue("availability").jsonPrimitive.content)
        assertFalse(large.containsKey("text"))
    }

    @Test fun deeplyNestedOrMalformedStoredJsonIsRejectedBeforeTreeParsing() {
        assertNull(SessionExportJson.objectOrNull("{".repeat(10000)))
        assertNull(SessionExportJson.objectOrNull("{broken"))
        assertEquals(
            "value",
            SessionExportJson
                .objectOrNull("""{"key":"value"}""")
                ?.get("key")
                ?.jsonPrimitive
                ?.content,
        )
    }
}
