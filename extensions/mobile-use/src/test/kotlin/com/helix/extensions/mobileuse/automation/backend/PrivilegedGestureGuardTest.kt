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
    @Test fun wholeTaskCoordinatesNeedTheSameTaskAndNeverBecomeRestrictedWindowCoordinates() {
        val visual =
            target.copy(
                windowId = -1,
                displayId = 7,
                displaySession = "owned",
                semanticAvailable = false,
                taskId = 42,
            )
        val original = request.copy(target = visual)
        val guard = PrivilegedGestureGuard(original, { visual }, { visual.gestureDisplay() }, { emptyList() })
        assertTrue(guard.ready(false))
        val moved =
            PrivilegedGestureGuard(original, { visual.copy(taskId = 43) }, { visual.gestureDisplay() }, { emptyList() })
        assertFalse(moved.ready(false))
        val restricted =
            PrivilegedGestureGuard(
                original.copy(wholeDisplay = false),
                { visual },
                { visual.gestureDisplay() },
                { emptyList() },
            )
        assertFalse(restricted.ready(false))
    }

    private val target = AutomationDisplayTarget("app", 7, 0, 100, 200, 0, AutomationNodeBounds(0, 0, 100, 200))
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

    @Test fun changedWindowOrMappingBeforeDownRejectsWithoutInput() {
        for (current in listOf(null, shade, target.copy(rotation = 1), target.copy(displaySession = "replaced"))) {
            val guard = PrivilegedGestureGuard(request, { current }, { target.gestureDisplay() }, { listOf(own) })
            val runner =
                PrivilegedGestureRunner(
                    { 0 },
                    {},
                    { true },
                    guard::ready,
                    { _, _ -> error("Unexpected input") },
                    { guard.refusal },
                )
            assertEquals(
                "TARGET_CHANGED",
                runner.execute(privilegedGesturePlan(request.strokes)),
            )
        }
    }

    @Test fun aSecondTapChecksIdentityAndDoesNotReplayAPartialAction() {
        val taps =
            listOf(
                AutomationStroke(listOf(AutomationPoint(50f, 50f)), 0, 60),
                AutomationStroke(listOf(AutomationPoint(50f, 50f)), 160, 60),
            )
        for (switchWindow in listOf(false, true)) {
            var current = target
            var reads = 0
            var time = 0L
            val events = mutableListOf<Int>()
            val guard =
                PrivilegedGestureGuard(
                    request.copy(strokes = taps),
                    {
                        reads++
                        current
                    },
                    { target.gestureDisplay() },
                    { listOf(own) },
                )
            val runner =
                PrivilegedGestureRunner(
                    { time },
                    { time += it },
                    { true },
                    guard::ready,
                    { event, _ ->
                        events += event.action
                        if (switchWindow && event.action == 1) current = shade
                        true
                    },
                    { guard.refusal },
                )
            assertEquals(
                if (switchWindow) "OUTCOME_UNKNOWN" else "DISPATCHED",
                runner.execute(privilegedGesturePlan(taps)),
            )
            assertEquals(if (switchWindow) listOf(0, 1) else listOf(0, 1, 0, 1), events.filter { it != 2 })
            assertEquals(2, reads)
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
        var current = target
        var windows = listOf(own)
        val guard =
            PrivilegedGestureGuard(
                request.copy(wholeDisplay = false),
                { current },
                { target.gestureDisplay() },
                { windows },
            )
        assertTrue(guard.ready(true))
        current = shade
        assertFalse(guard.ready(true))
        current = target
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
                current = target.copy(windowId = 8)
                true
            })
        assertEquals("DISPATCHED", runner.execute(privilegedGesturePlan(request.strokes)))
        assertEquals(1, events.last())
        assertTrue(3 !in events)
    }
}
