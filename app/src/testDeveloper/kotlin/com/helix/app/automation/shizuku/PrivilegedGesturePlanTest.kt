package com.helix.app.automation.shizuku

import com.helix.tools.automation.AutomationPoint
import com.helix.tools.automation.AutomationStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedGesturePlanTest {
    private fun stroke(
        start: Long,
        duration: Long,
    ) = AutomationStroke(
        listOf(AutomationPoint(1f, 2f), AutomationPoint(9f, 8f)),
        start,
        duration,
    )

    @Test fun dragFinishesAtExactEndpointWithBalancedTouch() {
        val plan = privilegedGesturePlan(listOf(stroke(0, 101)))
        assertEquals(0, plan.first().action)
        assertEquals(1, plan.last().action)
        assertEquals(101L, plan.last().time)
        assertEquals(
            AutomationPoint(9f, 8f),
            plan
                .last()
                .touches
                .single()
                .point,
        )
        assertTrue(plan.zipWithNext().all { (a, b) -> a.time <= b.time })
    }

    @Test fun staggeredMultiTouchKeepsPointerIdsThroughUpEvents() {
        val plan = privilegedGesturePlan(listOf(stroke(0, 100), stroke(20, 100)))
        val secondDown = plan.single { it.action and 255 == 5 }
        assertEquals(20L, secondDown.time)
        assertEquals(1, secondDown.action shr 8)
        assertEquals(listOf(0, 1), secondDown.touches.map { it.id })
        val firstUp = plan.single { it.action and 255 == 6 }
        assertEquals(100L, firstUp.time)
        assertEquals(0, firstUp.action shr 8)
        assertEquals(
            1,
            plan
                .last()
                .touches
                .single()
                .id,
        )
        assertEquals(1, plan.last().action)
    }

    @Test fun separateTouchesRestartAndOverflowCannotAllocateAPlan() {
        val plan = privilegedGesturePlan(listOf(stroke(0, 20), stroke(100, 20)))
        assertEquals(2, plan.count { it.action == 0 })
        assertEquals(2, plan.count { it.action == 1 })
        assertThrows(
            IllegalArgumentException::class.java,
        ) { privilegedGesturePlan(listOf(stroke(10_000, Long.MAX_VALUE))) }
        assertThrows(IllegalArgumentException::class.java) { privilegedGesturePlan(List(11) { stroke(0, 1) }) }
    }
}
