package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedGestureRunnerTest {
    private val plan = privilegedGesturePlan(listOf(AutomationStroke(listOf(AutomationPoint(1f, 1f)), 0, 100)))

    @Test fun cancellationAfterDownSendsCancelEvenWhenGrantIsGone() {
        var time = 0L
        var allowed = true
        val actions = mutableListOf<Int>()
        val result =
            PrivilegedGestureRunner({ time }, { time += it }, { allowed }, { true }, { step, _ ->
                actions.add(step.action)
                if (step.action == 0) allowed = false
                true
            }).execute(plan)
        assertEquals("OUTCOME_UNKNOWN", result)
        assertEquals(listOf(0, 3), actions)
    }

    @Test fun preDispatchFailureNeverInjectsAndCleanupFailureIsUnknown() {
        val refused = PrivilegedGestureRunner({ 0 }, {}, { false }, { true }, { _, _ -> error("unexpected input") })
        assertEquals("NOT_DISPATCHED", refused.execute(plan))
        val actions = mutableListOf<Int>()
        val failed =
            PrivilegedGestureRunner({ 0 }, {}, { true }, { true }, { step, _ ->
                actions.add(step.action)
                false
            })
        assertEquals("OUTCOME_UNKNOWN", failed.execute(plan))
        assertEquals(listOf(0, 3), actions)
    }

    @Test fun fullSequenceReleasesWithUpAndDelayedStartCanCancelBeforeAnyInput() {
        var time = 0L
        val actions = mutableListOf<Int>()
        val runner =
            PrivilegedGestureRunner({ time }, { time += it }, { true }, { true }, { step, _ ->
                actions.add(step.action)
                true
            })
        assertEquals("DISPATCHED", runner.execute(plan))
        assertEquals(1, actions.last())
        assertTrue(3 !in actions)
        time = 0
        val delayed = plan.map { it.copy(time = it.time + 1000) }
        val cancel =
            PrivilegedGestureRunner({ time }, { time += it }, { time < 32 }, { true }, { _, _ ->
                error("input before authorization")
            })
        assertEquals("NOT_DISPATCHED", cancel.execute(delayed))
        assertEquals(32L, time)
    }
}
