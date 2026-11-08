package com.helix.app.documents

import androidx.test.filters.SdkSuppress
import com.helix.core.model.CancelSignal
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolDispatchOutcome
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real document dispatch → Room snapshot → workspace/SAF → Android resolver, not a fake executor pool. */
@SdkSuppress(minSdkVersion = 29)
class SafSnapshotCapacityDeviceTest {
    @Test fun cancellationRetainsPhysicalCapacityAndStagingUntilProviderActuallyReturns() = exercise(false)

    @Test fun timeoutRetainsPhysicalCapacityAndStagingUntilProviderActuallyReturns() = exercise(true)

    private fun exercise(timeout: Boolean) =
        SafSnapshotCapacityFixture().use { fixture ->
            fixture.retainOtherSnapshot()
            fixture.provider.hold()
            abandonFullCapacity(fixture, timeout)
            assertSaturated(fixture)
            fixture.assertIndependentCommitAndGc(SafSnapshotCapacityFixture.CAPACITY)
            assertTrue(
                fixture.storage.documentSnapshots
                    .listBySession(SafSnapshotCapacityFixture.SELECTED)
                    .isEmpty(),
            )
            assertEquals(1, fixture.dispatch.exited.get()) // Only the earlier retained snapshot has physically exited.
            assertTrue(fixture.provider.interrupted.get() >= SafSnapshotCapacityFixture.CAPACITY)
            fixture.provider.release()
            awaitSnapshotCondition { fixture.dispatch.entered.get() == fixture.dispatch.exited.get() }
            assertTrue(fixture.stages().isEmpty())
            assertTrue(
                fixture.storage.documentSnapshots
                    .listBySession(SafSnapshotCapacityFixture.SELECTED)
                    .isEmpty(),
            )
            assertFullCapacityRecovered(fixture)
        }

    private fun abandonFullCapacity(
        fixture: SafSnapshotCapacityFixture,
        timeout: Boolean,
    ) {
        repeat(SafSnapshotCapacityFixture.CAPACITY) { index ->
            val cancelled = AtomicBoolean(false)
            val signal =
                object : CancelSignal {
                    override fun isCancelled(): Boolean = cancelled.get()
                }
            val result =
                fixture.callers.submit<ToolDispatchOutcome> {
                    fixture.dispatch.dispatch(
                        "blocked-$index",
                        SafSnapshotCapacityFixture.SELECTED,
                        signal,
                        if (timeout) 350 else 20_000,
                    )
                }
            awaitSnapshotCondition { fixture.provider.active.get() == index + 1 }
            if (!timeout) cancelled.set(true)
            val terminal = result.get(5, TimeUnit.SECONDS)
            assertTrue(terminal is ToolDispatchOutcome.ExecutionFailed)
            terminal as ToolDispatchOutcome.ExecutionFailed
            assertEquals(
                if (timeout) DispatchOutcomeCode.TIMEOUT else DispatchOutcomeCode.CANCELLED_AFTER_START,
                terminal.code,
            )
            assertFalse(terminal.sideEffectFree) // Logical cancellation is not physical-exit or rollback proof.
            assertEquals(index + 1, fixture.stages().size)
        }
    }

    private fun assertSaturated(fixture: SafSnapshotCapacityFixture) {
        val entered = fixture.dispatch.entered.get()
        repeat(10) { index ->
            val rejected =
                fixture.dispatch.dispatch(
                    "rejected-$index",
                    SafSnapshotCapacityFixture.SELECTED,
                    NoCancellation,
                )
            assertTrue(rejected is ToolDispatchOutcome.ExecutionFailed && rejected.sideEffectFree)
            assertTrue((rejected as ToolDispatchOutcome.ExecutionFailed).detail.startsWith("EXECUTOR_SATURATED"))
            val detail =
                requireNotNull(
                    fixture.dispatch.audit
                        .last()
                        .executionDetail,
                )
            assertEquals(SafSnapshotCapacityFixture.CAPACITY, detail.getValue("executorRunning").jsonPrimitive.int)
            assertEquals(
                SafSnapshotCapacityFixture.CAPACITY,
                detail.getValue("executorAbandonedRunning").jsonPrimitive.int,
            )
            assertEquals(entered, fixture.dispatch.entered.get())
            assertEquals(SafSnapshotCapacityFixture.CAPACITY, fixture.provider.active.get())
            assertEquals(SafSnapshotCapacityFixture.CAPACITY, fixture.stages().size)
        }
    }

    private fun assertFullCapacityRecovered(fixture: SafSnapshotCapacityFixture) {
        fixture.provider.hold()
        val calls =
            (0 until SafSnapshotCapacityFixture.CAPACITY).map { index ->
                fixture.callers.submit<ToolDispatchOutcome> {
                    fixture.dispatch.dispatch("restored-$index", SafSnapshotCapacityFixture.SELECTED, NoCancellation)
                }
            }
        awaitSnapshotCondition { fixture.provider.active.get() == SafSnapshotCapacityFixture.CAPACITY }
        assertEquals(SafSnapshotCapacityFixture.CAPACITY, fixture.stages().size)
        fixture.provider.release()
        calls.forEach { assertTrue(it.get(15, TimeUnit.SECONDS) is ToolDispatchOutcome.Succeeded) }
        awaitSnapshotCondition { fixture.dispatch.entered.get() == fixture.dispatch.exited.get() }
        fixture.storage.collectGarbage(0, Long.MAX_VALUE)
        assertTrue(fixture.stages().isEmpty())
        assertEquals(
            SafSnapshotCapacityFixture.CAPACITY,
            fixture.storage.documentSnapshots
                .listBySession(SafSnapshotCapacityFixture.SELECTED)
                .size,
        )
        assertEquals(
            1,
            fixture.storage.documentSnapshots
                .listBySession(SafSnapshotCapacityFixture.OTHER)
                .size,
        )
    }
}
