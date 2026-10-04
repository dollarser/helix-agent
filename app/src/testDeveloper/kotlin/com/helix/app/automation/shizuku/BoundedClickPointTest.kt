package com.helix.app.automation.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BoundedClickPointTest {
    @Test fun samplingStaysNearTheCenterAndInsideEveryTarget() {
        val random = Random(17)
        for (width in listOf(1, 2, 19, 20, 21, 79, 80, 1000)) {
            for (height in listOf(1, 19, 20, 80, 1000)) {
                val target = ShizukuUiTarget("0", 7, 11, 7 + width, 11 + height)
                repeat(100) {
                    val point = target.sampleClickPoint(random)
                    assertTrue(point.x in target.left until target.right)
                    assertTrue(point.y in target.top until target.bottom)
                    assertTrue(kotlin.math.abs(point.x - target.x) <= minOf(width / 20, 4))
                    assertTrue(kotlin.math.abs(point.y - target.y) <= minOf(height / 20, 4))
                }
            }
        }
    }

    @Test fun smallTargetsKeepTheirCenterAndNarrowAxesDoNotMove() {
        val target = ShizukuUiTarget("0", 0, 0, 1, 19)
        repeat(20) { assertEquals(BoundedClickPoint(0, 9), target.sampleClickPoint(Random(it))) }
        val narrow = target.copy(bottom = 1000)
        repeat(20) { assertEquals(0, narrow.sampleClickPoint(Random(it)).x) }
    }

    @Test fun largerTargetsVaryWithinTheFourPixelCap() {
        val target = ShizukuUiTarget("0", 0, 0, 1000, 1000)
        val random = Random(31)
        val points = List(200) { target.sampleClickPoint(random) }
        assertTrue(points.map { it.x }.toSet().size > 1)
        assertTrue(points.map { it.y }.toSet().size > 1)
        assertTrue(points.all { it.x in 496..504 && it.y in 496..504 })
    }
}
