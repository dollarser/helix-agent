package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class AutomationGesturePatternsTest {
    private val center = AutomationPoint(500f, 500f)

    @Test fun pinchScaleAndRotationPreserveTheIntendedCenter() {
        val pinch = AutomationGesturePatterns.pinch(center, 100f, 2f, 300)
        assertEquals(listOf(450f, 550f), pinch.map { it.points.first().x })
        assertEquals(listOf(400f, 600f), pinch.map { it.points.last().x })
        val rotated = AutomationGesturePatterns.rotate(center, 100f, 90f, 300)
        rotated.forEach { stroke ->
            stroke.points.forEach { assertEquals(50f, hypot(it.x - 500, it.y - 500), 0.001f) }
        }
        assertEquals(550f, rotated[0].points.last().y, 0.001f)
        assertEquals(450f, rotated[1].points.last().y, 0.001f)
    }

    @Test fun threeFingersMoveTogetherAndDoubleTapHasARealGap() {
        val swipe = AutomationGesturePatterns.swipe(center, AutomationPoint(500f, 700f), 300, 3, 60f)
        assertEquals(3, swipe.size)
        assertTrue(swipe.all { it.startMillis == 0L && it.durationMillis == 300L })
        assertEquals(listOf(560f, 500f, 440f), swipe.map { it.points.first().x })
        assertTrue(swipe.all { it.points.last().y - it.points.first().y == 200f })
        assertEquals(listOf(0L, 160L), AutomationGesturePatterns.tap(center, twice = true).map { it.startMillis })
    }

    @Test fun invalidGeometryAndOutOfScopeGeneratedPathsAreNotClamped() {
        assertThrows(IllegalArgumentException::class.java) { AutomationGesturePatterns.pinch(center, 0f, 2f, 300) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { AutomationGesturePatterns.rotate(center, 100f, Float.NaN, 300) }
        assertThrows(IllegalArgumentException::class.java) { AutomationGesturePatterns.swipe(center, center, 300) }
        assertThrows(IllegalArgumentException::class.java) { AutomationGesturePatterns.tap(center, 600, true) }
        val strokes = AutomationGesturePatterns.pinch(AutomationPoint(10f, 10f), 100f, 2f, 300)
        val target = AutomationDisplayTarget("fixture", 1, 0, 100, 100, 0, AutomationNodeBounds(0, 0, 100, 100))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { validateAutomationGesture(strokes, target, true, 10, 10_000) }
    }
}
