package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AutomationOperationSelectionTest {
    @Test fun readyRootWithoutOperationDoesNotMaskCapableShizuku() {
        val root = Backend(setOf(AutomationDeviceOperation.OBSERVE))
        val shizuku = Backend(AutomationDeviceOperation.entries.toSet())
        val selection = AutomationBackendSelection(root, shizuku)
        assertSame(root, selection.select(AutomationDeviceOperation.OBSERVE))
        assertSame(shizuku, selection.select(AutomationDeviceOperation.SNAPSHOT))
        assertSame(shizuku, selection.select(AutomationDeviceOperation.GESTURE))
        assertEquals(AutomationClickBackend.SHIZUKU, selection.preferred(AutomationDeviceOperation.SNAPSHOT))
        assertEquals(0, root.calls + shizuku.calls)
    }

    @Test fun permissionAndConnectionLossAreReevaluatedBeforeSelection() {
        val root = Backend(AutomationDeviceOperation.entries.toSet())
        val shizuku = Backend(AutomationDeviceOperation.entries.toSet())
        val selection = AutomationBackendSelection(root, shizuku)
        for (state in AutomationBackendState.entries) {
            root.availability = state
            assertSame(
                if (state ==
                    AutomationBackendState.READY
                ) {
                    root
                } else {
                    shizuku
                },
                selection.select(AutomationDeviceOperation.SNAPSHOT),
            )
        }
        shizuku.availability = AutomationBackendState.LOST
        assertNull(selection.select(AutomationDeviceOperation.SNAPSHOT))
        assertEquals(0, root.calls + shizuku.calls)
    }

    @Test fun operationsWithoutTargetObservationCannotBeSelected() {
        val root = Backend(setOf(AutomationDeviceOperation.GESTURE))
        assertNull(AutomationBackendSelection(root, null).select(AutomationDeviceOperation.GESTURE))
    }

    private class Backend(
        override val deviceOperations: Set<AutomationDeviceOperation>,
    ) : AutomationPrivilegedBackend {
        var availability = AutomationBackendState.READY
        var calls = 0

        override fun state() = availability

        override fun click(
            selector: AutomationPrivilegedSelector,
            allowed: (Int, Int, Int) -> Boolean,
            mayFinish: () -> Boolean,
        ): AutomationActionResult {
            calls++
            error("Selection must not execute")
        }
    }
}
