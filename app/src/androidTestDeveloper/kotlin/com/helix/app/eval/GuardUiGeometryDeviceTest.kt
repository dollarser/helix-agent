package com.helix.app.eval

import android.graphics.Rect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary regression for the extracted benchmark guard, without model or external effects. */
class GuardUiGeometryDeviceTest {
    private val bounds = Rect(10, 20, 30, 40)

    @Test
    fun rectangleIncludesOnlyLeftAndTopEdges() {
        assertTrue(GuardUiGeometry.contains(bounds, point("10", "20")))
        assertTrue(GuardUiGeometry.contains(bounds, point("29.999", "39.999")))
        assertFalse(GuardUiGeometry.contains(bounds, point("30", "20")))
        assertFalse(GuardUiGeometry.contains(bounds, point("10", "40")))
        assertFalse(GuardUiGeometry.contains(bounds, point("9.999", "20")))
        assertFalse(GuardUiGeometry.contains(bounds, point("10", "19.999")))
    }

    @Test
    fun missingAndNonfiniteCoordinatesAreRejected() {
        assertFalse(GuardUiGeometry.contains(bounds, JsonObject(emptyMap())))
        assertFalse(GuardUiGeometry.contains(bounds, point("null", "20")))
        for (value in listOf("\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "1e999", "\"bad\"")) {
            assertFalse(GuardUiGeometry.contains(bounds, point(value, "20")))
            assertFalse(GuardUiGeometry.contains(bounds, point("10", value)))
        }
    }

    @Test
    fun everyStrokeAndPointMustRemainInsideNonemptyBounds() {
        assertFalse(GuardUiGeometry.withinBounds(json("{}"), bounds))
        assertFalse(GuardUiGeometry.withinBounds(json("{\"strokes\":[]}"), bounds))
        assertFalse(GuardUiGeometry.withinBounds(json("{\"strokes\":[{\"points\":[]}]}"), bounds))
        val inside = gesture("{\"x\":10,\"y\":20},{\"x\":29,\"y\":39}")
        assertTrue(GuardUiGeometry.withinBounds(inside, bounds))
        assertFalse(GuardUiGeometry.withinBounds(inside, Rect()))
        assertFalse(GuardUiGeometry.withinBounds(gesture("{\"x\":10,\"y\":20},{\"x\":30,\"y\":39}"), bounds))
        val mixed = json("{\"strokes\":[{\"points\":[{\"x\":10,\"y\":20}]},{\"points\":[]}]}")
        assertFalse(GuardUiGeometry.withinBounds(mixed, bounds))
    }

    @Test
    fun brightnessRequiresOneHorizontalMultiPointStroke() {
        assertNull(GuardBrightnessGeometry.gesturePoints(json("{}")))
        assertNull(GuardBrightnessGeometry.gesturePoints(gesture("{\"x\":10,\"y\":20}")))
        val horizontal =
            requireNotNull(
                GuardBrightnessGeometry.gesturePoints(
                    gesture("{\"x\":10,\"y\":20},{\"x\":29,\"y\":20}"),
                ),
            )
        assertTrue(GuardBrightnessGeometry.horizontalPath(horizontal, bounds))
        val diagonal =
            requireNotNull(
                GuardBrightnessGeometry.gesturePoints(
                    gesture("{\"x\":10,\"y\":20},{\"x\":29,\"y\":21}"),
                ),
            )
        assertFalse(GuardBrightnessGeometry.horizontalPath(diagonal, bounds))
    }

    @Test
    fun brightnessObservationRequiresSingleVisibleEnabledSlider() {
        val slider = """{"viewId":"com.android.systemui:id/slider","canSetProgress":true,
            "enabled":true,"text":"Brightness","bounds":{"left":10,"top":20,"right":30,"bottom":40}}"""
        assertEquals(bounds, GuardBrightnessGeometry.observedRectangle(json("{\"nodes\":[$slider]}")))
        assertNull(GuardBrightnessGeometry.observedRectangle(json("{\"nodes\":[$slider,$slider]}")))
        assertNull(GuardBrightnessGeometry.observedRectangle(json("{\"truncated\":true,\"nodes\":[$slider]}")))
        for (flag in listOf("redacted", "offscreen")) {
            val unavailable = slider.replace("\"enabled\":true", "\"enabled\":true,\"$flag\":true")
            assertNull(GuardBrightnessGeometry.observedRectangle(json("{\"nodes\":[$unavailable]}")))
        }
        val disabled = slider.replace("\"enabled\":true", "\"enabled\":false")
        assertNull(GuardBrightnessGeometry.observedRectangle(json("{\"nodes\":[$disabled]}")))
    }

    @Test
    fun onlyPlainSystemUiBehindScrimCanBeExcludedFromBrightnessOverlaps() {
        val background = Rect(0, 0, 100, 100)
        val packageName = "com.android.systemui"
        val behind = "com.android.systemui:id/scrim_behind"
        val scrimClass = "android.view.View"
        assertTrue(
            GuardBrightnessGeometry.isKnownBehindScrim(packageName, behind, scrimClass, background, bounds, true),
        )
        for (resource in listOf(
            "com.android.systemui:id/scrim_in_front",
            "com.android.systemui:id/scrim_notifications",
            "com.android.systemui:id/unknown",
            null,
        )) {
            assertFalse(
                GuardBrightnessGeometry.isKnownBehindScrim(packageName, resource, scrimClass, background, bounds, true),
            )
        }
        assertFalse(
            GuardBrightnessGeometry.isKnownBehindScrim("other.package", behind, scrimClass, background, bounds, true),
        )
        assertFalse(
            GuardBrightnessGeometry.isKnownBehindScrim(
                packageName,
                behind,
                "android.widget.Button",
                background,
                bounds,
                true,
            ),
        )
        assertFalse(
            GuardBrightnessGeometry.isKnownBehindScrim(packageName, behind, scrimClass, background, bounds, false),
        )
        assertFalse(
            GuardBrightnessGeometry.isKnownBehindScrim(
                packageName,
                behind,
                scrimClass,
                Rect(11, 0, 100, 100),
                bounds,
                true,
            ),
        )
        assertFalse(GuardBrightnessGeometry.isKnownBehindScrim(packageName, behind, scrimClass, Rect(), bounds, true))
        assertFalse(
            GuardBrightnessGeometry.isKnownBehindScrim(packageName, behind, scrimClass, background, Rect(), true),
        )
    }

    private fun point(
        x: String,
        y: String,
    ): JsonObject = json("{\"x\":$x,\"y\":$y}")

    private fun gesture(points: String): JsonObject = json("{\"strokes\":[{\"points\":[$points]}]}")

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value) as JsonObject
}
