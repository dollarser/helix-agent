package com.helix.extensions.mobileuse.automation

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationDeviceContractTest {
    private val target =
        AutomationDisplayTarget("com.example.app", 5, 0, 1080, 2400, 0, AutomationNodeBounds(0, 100, 1080, 2100))

    @Test fun newObservationGrantRotationOrWindowCannotReuseFrame() {
        var id = 0
        val registry = AutomationFrameRegistry { "frame-${++id}" }
        val first = registry.issue("grant-1", target)
        assertEquals(first, registry.resolve(first.token, "grant-1", target))
        assertNull(registry.resolve(first.token, "grant-2", target))
        assertNull(registry.resolve(first.token, "grant-1", target.copy(rotation = 1)))
        assertNull(registry.resolve(first.token, "grant-1", target.copy(windowId = 9)))
        val second = registry.issue("grant-1", target)
        assertNull(registry.resolve(first.token, "grant-1", target))
        registry.invalidate()
        assertNull(registry.resolve(second.token, "grant-1", target))
    }

    @Test fun fullPhoneAllowsSystemAreaButAppGrantDoesNot() {
        val tap = listOf(AutomationStroke(listOf(AutomationPoint(100f, 50f)), 0, 100))
        validateAutomationGesture(tap, target, true, 10, 60_000)
        assertThrows(IllegalArgumentException::class.java) { validateAutomationGesture(tap, target, false, 10, 60_000) }
        val drag = listOf(AutomationStroke(listOf(AutomationPoint(100f, 150f), AutomationPoint(300f, 1000f)), 0, 1500))
        validateAutomationGesture(drag + drag, target, false, 10, 60_000)
    }

    @Test fun malformedGesturesAreRejectedBeforePlatformEntry() {
        for (point in listOf(
            AutomationPoint(Float.NaN, 0f),
            AutomationPoint(-1f, 100f),
            AutomationPoint(1080f, 100f),
        )) {
            val stroke = AutomationStroke(listOf(point), 0, 100)
            assertThrows(IllegalArgumentException::class.java) {
                validateAutomationGesture(listOf(stroke), target, true, 10, 60_000)
            }
        }
        for (stroke in listOf(
            AutomationStroke(emptyList(), 0, 10),
            AutomationStroke(listOf(AutomationPoint(50f, 200f)), Long.MAX_VALUE, 10),
            AutomationStroke(listOf(AutomationPoint(50f, 200f)), 0, 0),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                validateAutomationGesture(listOf(stroke), target, true, 10, 60_000)
            }
        }
    }

    @Test fun optionsAllowUserStoppedAndLongSessionsWithoutSilentClamping() {
        val unlimited = AutomationSessionOptions.parse("0", "0")
        assertTrue(unlimited.ttl.isZero)
        assertEquals(0, unlimited.maxActions)
        val longer = AutomationSessionOptions.parse("720", "100000")
        assertEquals(12L, longer.ttl.toHours())
        assertEquals(100000, longer.maxActions)
        for (bad in listOf("-1", "NaN", "9223372036854775807")) {
            assertThrows(IllegalArgumentException::class.java) { AutomationSessionOptions.parse(bad, "10") }
        }
    }

    @Test fun callbackOwnershipDisposesLateAndDuplicateResults() {
        val disposed = mutableListOf<String>()
        val pending = PendingAutomationResult<String> { disposed += it }
        pending.complete("first")
        assertEquals("first", pending.await(call(Instant.now().plusSeconds(1))))
        pending.complete("duplicate")
        assertEquals(listOf("duplicate"), disposed)
        val late = PendingAutomationResult<String> { disposed += it }
        assertNull(late.await(call(Instant.EPOCH)))
        late.complete("late")
        assertEquals(listOf("duplicate", "late"), disposed)
    }

    @Test fun abandonDisposesBufferedResultExactlyOnce() {
        var count = 0
        val pending = PendingAutomationResult<Int> { count++ }
        pending.complete(1)
        pending.abandon()
        pending.abandon()
        assertEquals(1, count)
    }

    @Test fun lateCallbacksCannotReleaseAnotherPhysicalOperation() {
        val slot = AutomationPhysicalSlot()
        val first = requireNotNull(slot.acquire())
        assertNull(slot.acquire())
        slot.release(first)
        val next = requireNotNull(slot.acquire())
        slot.release(first)
        assertNull(slot.acquire())
        slot.release(next)
        assertTrue(slot.acquire() != null)
    }

    @Test fun scopedGesturesRejectAnUnapprovedOverlayButWholePhoneRemainsUsable() {
        val scope =
            com.helix.core.policy
                .AutomationSessionScope(setOf(target.packageName), emptySet(), 0, Instant.MAX)
        val tap = listOf(AutomationStroke(listOf(AutomationPoint(100f, 200f)), 0, 100))
        val windows =
            listOf(
                AutomationWindowRegion(target.windowId, 1, target.packageName, target.bounds),
                AutomationWindowRegion(9, 2, "com.other.overlay", AutomationNodeBounds(50, 100, 500, 500)),
            )
        org.junit.Assert.assertFalse(gestureWindowsPermitted(scope, target, tap, windows))
        assertTrue(
            gestureWindowsPermitted(
                scope.copy(allowedPackages = emptySet(), allApplications = true),
                target,
                tap,
                windows,
            ),
        )
        assertTrue(gestureWindowsPermitted(scope, target, tap, windows.take(1)))
    }

    @Test fun cancelledCallbackCannotBeDeliveredAsASuccess() {
        val disposed = mutableListOf<String>()
        val pending = PendingAutomationResult<String> { disposed += it }
        pending.complete("result")
        var checks = 0
        val cancelled =
            object : com.helix.tools.framework.CancelSignal {
                override fun isCancelled(): Boolean = ++checks > 1
            }
        assertNull(pending.await(call(Instant.now().plusSeconds(1)).copy(cancel = cancelled)))
        assertEquals(listOf("result"), disposed)
    }

    private fun call(deadline: Instant) =
        ExecutableToolCall(
            "call",
            "ui.screenshot",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            deadline,
            NoCancellation,
        )
}
