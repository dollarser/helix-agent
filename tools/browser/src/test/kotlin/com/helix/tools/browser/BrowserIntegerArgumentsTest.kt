package com.helix.tools.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserIntegerArgumentsTest {
    @Test
    fun exactIntegralRepresentationsAreAcceptedWithinBounds() {
        for (token in listOf("2", "2.0", "2e0", "20e-1")) {
            assertEquals(2, intArg(args(token), "dx", -3, 3))
        }
    }

    @Test
    fun malformedFractionalOverflowAndOutOfBoundsValuesAreRejected() {
        for (token in listOf("null", "true", "[]", "{}", "\"2\"", "1.5", "2147483648", "4", "1e10001")) {
            assertNull(intArg(args(token), "dx", -3, 3))
        }
    }

    private fun args(token: String) = Json.parseToJsonElement("""{"dx":$token}""").jsonObject
}
