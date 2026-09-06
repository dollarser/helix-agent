package com.helix.spikes.orchestration

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** API compatibility smoke for the isolated HXA-105 state/recovery model. */
@RunWith(AndroidJUnit4::class)
class BoundedOrchestrationDeviceTest {
    @Test
    fun runningChildBecomesNeedsReviewAfterProcessReconstruction() {
        val journal = InMemoryChildJournal()
        val budget = ParentBudget(2, 100, 2, 1_000)
        BoundedChildCoordinator("parent", budget, journal).apply {
            spawn("child", 1, "bounded task", byteArrayOf(1, 2, 3))
            start("child")
        }

        val recovered = BoundedChildCoordinator("parent", budget, journal).recover()
        assertEquals(ChildState.NEEDS_REVIEW, recovered.single().state)
    }

    @Test
    fun injectionCannotAddWriteTool() {
        val coordinator =
            BoundedChildCoordinator(
                "parent",
                ParentBudget(2, 100, 2, 1_000),
                InMemoryChildJournal(),
            )
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.admitTools(listOf(ReadOnlyTool("injected-write", SpikeOperation.MUTATION, SpikeRisk.L0)))
        }
    }
}
