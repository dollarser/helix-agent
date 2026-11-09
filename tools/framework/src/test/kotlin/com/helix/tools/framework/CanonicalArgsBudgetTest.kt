package com.helix.tools.framework

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalArgsBudgetTest {
    @Test fun preservesNumericLexemesEvenWhenExecutionWillRejectTheirResourceCost() {
        val original = """{"x":1e99999999999999999999,"y":1.00,"z":-0}"""
        assertEquals(original, CanonicalArgs.canonicalize(Json.parseToJsonElement(original)))
    }

    @Test fun rejectsDeepTreesBeforeRecursiveEncoding() {
        var value: JsonElement = JsonPrimitive(0)
        repeat(10000) { value = JsonArray(listOf(value)) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalArgs.canonicalize(value) }
    }
}
