package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke
import com.helix.extensions.mobileuse.automation.AutomationWindowRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedGestureGuardTest {
    private val target = AutomationDisplayTarget("app", 7, 0, 100, 200, 0, AutomationNodeBounds(0, 0, 100, 200), "v1")
    private val own = AutomationWindowRegion(7, 1, "app", target.bounds)
    private val shade = target.copy(packageName = "system", windowId = 8, bounds = AutomationNodeBounds(0, 0, 100, 140))
    private val request =
        AutomationDeviceRequest(
            AutomationDeviceOperation.GESTURE,
            target,
            listOf(AutomationStroke(listOf(AutomationPoint(50f, 1f), AutomationPoint(50f, 130f)), 0, 100)),
            wholeDisplay = true,
        )

    @Test fun shadeTakingFocusAfterDownDoesNotCancelTheWholeDisplaySwipe() {
        var current: AutomationDisplayTarget? = target
        val guard = PrivilegedGestureGuard(request, { current }, { target.gestureDisplay() }, { listOf(own) })
        var time = 0L
        val events = mutableListOf<Int>()
        val runner =
            PrivilegedGestureRunner({ time }, { time += it }, { true }, guard::ready, { event, _ ->
                events += event.action
                // A disappearing root during the transition must not cancel an already authorized touch.
                current = if (event.action == 0) null else shade
                true
            })
        assertEquals("DISPATCHED", runner.execute(privilegedGesturePlan(request.strokes)))
        assertEquals(0, events.first())
        assertEquals(1, events.last())
        assertTrue(3 !in events)
    }

    @Test fun changedWindowOrRevisionBeforeDownStillRejectsWithoutInput() {
        for (current in listOf(null, shade, target.copy(revision = "v2"))) {
            val guard = PrivilegedGestureGuard(request, { current }, { target.gestureDisplay() }, { listOf(own) })
            val runner =
                PrivilegedGestureRunner({ 0 }, {}, { true }, guard::ready, { _, _ -> error("Unexpected input") })
            assertEquals("NOT_DISPATCHED", runner.execute(privilegedGesturePlan(request.strokes)))
        }
    }

    @Test fun displayMappingChangeAfterDownCancelsAndRemainsUnknown() {
        val original = target.gestureDisplay()
        for (changed in listOf(
            original.copy(id = 1),
            original.copy(width = 99),
            original.copy(height = 199),
            original.copy(rotation = 1),
        )) {
            var display = original
            var time = 0L
            val events = mutableListOf<Int>()
            val guard = PrivilegedGestureGuard(request, { target }, { display }, { listOf(own) })
            val runner =
                PrivilegedGestureRunner({ time }, { time += it }, { true }, guard::ready, { event, _ ->
                    events += event.action
                    display = changed
                    true
                })
            assertEquals("OUTCOME_UNKNOWN", runner.execute(privilegedGesturePlan(request.strokes)))
            assertEquals(listOf(0, 3), events)
        }
    }

    @Test fun restrictedGesturesStillBindAppAndRejectCoveringPanels() {
        var current = target.copy(revision = "")
        var windows = listOf(own)
        val guard =
            PrivilegedGestureGuard(
                request.copy(wholeDisplay = false),
                { current },
                { target.gestureDisplay() },
                { windows },
            )
        assertTrue(guard.ready(true))
        current = shade.copy(revision = "")
        assertFalse(guard.ready(true))
        current = target.copy(revision = "")
        windows = listOf(own, AutomationWindowRegion(8, 2, "system", shade.bounds))
        assertFalse(guard.ready(true))
    }

    @Test fun restrictedGestureMayOpenAnotherWindowInsideTheSameApp() {
        var current = target
        val guard =
            PrivilegedGestureGuard(
                request.copy(wholeDisplay = false),
                { current },
                { target.gestureDisplay() },
                { listOf(own.copy(windowId = current.windowId)) },
            )
        var time = 0L
        val events = mutableListOf<Int>()
        val runner =
            PrivilegedGestureRunner({ time }, { time += it }, { true }, guard::ready, { event, _ ->
                events += event.action
                current = target.copy(windowId = 8, revision = "changed-content")
                true
            })
        assertEquals("DISPATCHED", runner.execute(privilegedGesturePlan(request.strokes)))
        assertEquals(1, events.last())
        assertTrue(3 !in events)
    }
}
