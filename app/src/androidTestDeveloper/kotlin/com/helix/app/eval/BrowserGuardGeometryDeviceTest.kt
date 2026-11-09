package com.helix.app.eval

import android.graphics.Rect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Predicate-only fixture regression: no model, device settings, user actions, or oracle effects. */
class BrowserGuardGeometryDeviceTest {
    private val bounds = Rect(10, 20, 100, 200)

    private fun admitted(json: String): Boolean =
        BrowserGuardGeometry.gestureInsideBounds(Json.parseToJsonElement(json).jsonObject, bounds)

    @Test
    fun admitsEveryPointInsideHalfOpenBounds() {
        assertTrue(admitted("""{"strokes":[{"points":[{"x":10,"y":20},{"x":99.9,"y":199.9}]}]}"""))
        assertTrue(admitted("""{"strokes":[{"points":[{"x":50,"y":50}]},{"points":[{"x":60,"y":60}]}]}"""))
    }

    @Test
    fun rejectsEdgesAndOneOutsidePointInAnyStroke() {
        for ((x, y) in listOf(9 to 20, 10 to 19, 100 to 20, 10 to 200)) {
            assertFalse(admitted("""{"strokes":[{"points":[{"x":$x,"y":$y}]}]}"""))
        }
        assertFalse(admitted("""{"strokes":[{"points":[{"x":50,"y":50}]},{"points":[{"x":100,"y":50}]}]}"""))
    }

    @Test
    fun rejectsMissingEmptyOrNonnumericCoordinates() {
        for (input in listOf(
            "{}",
            """{"strokes":[]}""",
            """{"strokes":[{}]}""",
            """{"strokes":[{"points":[]}]}""",
            """{"strokes":[{"points":[{"x":50}]}]}""",
            """{"strokes":[{"points":[{"x":"invalid","y":50}]}]}""",
            """{"strokes":[{"points":[{"x":"NaN","y":50}]}]}""",
            """{"strokes":[{"points":[{"x":"Infinity","y":50}]}]}""",
        )) {
            assertFalse(input, admitted(input))
        }
    }

    @Test
    fun malformedStructuredCoordinatesDoNotBecomeApproval() {
        for (input in listOf(
            """{"strokes":{}}""",
            """{"strokes":[1]}""",
            """{"strokes":[{"points":{}}]}""",
            """{"strokes":[{"points":[{"x":{},"y":50}]}]}""",
        )) {
            assertTrue(input, runCatching { admitted(input) }.isFailure)
        }
    }

    @Test
    fun emptyRectangleDoesNotAdmitCoordinates() {
        val input = Json.parseToJsonElement("""{"strokes":[{"points":[{"x":10,"y":20}]}]}""").jsonObject
        assertFalse(BrowserGuardGeometry.gestureInsideBounds(input, Rect(10, 20, 10, 20)))
    }
}
