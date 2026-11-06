package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetainedTaskPlanTest {
    private val records = listOf(RetainedDisplayTask(42, "com.example.app"))

    @Test fun recoveryNeverBecomesPermanentPermissionToMoveAUserReturnedTask() {
        val recovery = RetainedTaskRecovery(records, true)
        val primary = listOf(RetainedTaskLocation(42, 0, "com.example.app"))
        assertEquals(
            listOf(42),
            retainedTaskPlan(records, primary, 8, "com.example.app", false, recovery.remaining(primary, 8)),
        )
        recovery.dispatched(42)
        // A successful recovery or unknown move result never renews its one-time exception.
        assertEquals(
            emptyList<Int>(),
            retainedTaskPlan(records, primary, 8, "com.example.app", false, recovery.remaining(primary, 8)),
        )
        assertEquals(
            listOf(42),
            retainedTaskPlan(records, primary, 8, "com.example.app", true, recovery.remaining(primary, 8)),
        )
    }

    @Test fun anObservedRecoveryConsumesOnlyThatOriginalTask() {
        val history = records + RetainedDisplayTask(43, "com.example.app")
        val recovery = RetainedTaskRecovery(history, true)
        val current =
            listOf(RetainedTaskLocation(42, 8, "com.example.app"), RetainedTaskLocation(43, 0, "com.example.app"))
        assertEquals(setOf(43), recovery.remaining(current, 8))
        assertEquals(emptySet<Int>(), RetainedTaskRecovery(history, false).remaining(current, 8))
    }

    @Test fun sameTaskCanBeAdoptedOrMovedFromPrimary() {
        for (display in listOf(0, 8)) {
            assertEquals(
                listOf(42),
                retainedTaskPlan(
                    records,
                    listOf(RetainedTaskLocation(42, display, "com.example.app")),
                    8,
                    "com.example.app",
                    true,
                ),
            )
        }
    }

    @Test fun missingTaskChangedPackageOrUserMovedDisplayRefusesRelaunch() {
        for (current in listOf(
            emptyList(),
            listOf(RetainedTaskLocation(42, 0, "com.other.app")),
            listOf(RetainedTaskLocation(42, 9, "com.example.app")),
        )) {
            assertEquals(emptyList<Int>(), retainedTaskPlan(records, current, 8, "com.example.app", true))
        }
    }

    @Test fun survivingDisplayCannotStealTaskFromPrimaryWithoutPermission() {
        assertEquals(
            emptyList<Int>(),
            retainedTaskPlan(
                records,
                listOf(RetainedTaskLocation(42, 0, "com.example.app")),
                8,
                "com.example.app",
                false,
            ),
        )
        assertEquals(
            listOf(42),
            retainedTaskPlan(
                records,
                listOf(RetainedTaskLocation(42, 8, "com.example.app")),
                8,
                "com.example.app",
                false,
            ),
        )
    }

    @Test fun otherPackageHasNoRecoveryHistory() {
        assertNull(retainedTaskPlan(records, emptyList(), 8, "com.new.app", false))
    }

    @Test fun partialMultiTaskHistoryDoesNotMoveAnyPrefix() {
        assertEquals(
            emptyList<Int>(),
            retainedTaskPlan(
                records + RetainedDisplayTask(43, "com.example.app"),
                listOf(RetainedTaskLocation(42, 0, "com.example.app")),
                8,
                "com.example.app",
                true,
            ),
        )
    }
}
