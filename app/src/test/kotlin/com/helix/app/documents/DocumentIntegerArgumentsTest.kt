package com.helix.app.documents

import com.helix.extensions.documents.DocumentException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentIntegerArgumentsTest {
    @Test
    fun missingUsesDefaultAndIntegralTokensPreserveRequestedWindow() {
        assertEquals(9, DocumentIntegerArguments.read(JsonObject(emptyMap()), "page", 9))
        for (token in listOf("2", "2.0", "2e0", "20e-1")) {
            assertEquals(2, DocumentIntegerArguments.read(args(token), "page", 9))
        }
    }

    @Test
    fun malformedFractionalAndOverflowValuesFailInsteadOfDefaulting() {
        for (token in listOf("null", "true", "[]", "{}", "\"2\"", "1.5", "2147483648", "1e10001")) {
            assertThrows(DocumentException::class.java) { DocumentIntegerArguments.read(args(token), "page", 9) }
        }
    }

    private fun args(token: String) = Json.parseToJsonElement("""{"page":$token}""").jsonObject
}
