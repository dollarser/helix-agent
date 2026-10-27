package com.helix.runtime.proot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeExecutionCapacityTest {
    @Test fun fourTerminalsShareTheExistingSixSlotsWithJobsAndReservations() {
        val gate = RuntimeExecutionCapacity()
        repeat(4) { assertTrue(gate.reserveTerminal("pty$it")) }
        assertFalse(gate.reserveTerminal("pty-overflow"))
        assertTrue(gate.reserveJob("reserved"))
        assertTrue(gate.beginJob("running", false))
        assertFalse(gate.reserveJob("aggregate-overflow"))
        assertTrue(gate.beginJob("reserved", true))
        gate.releaseReservation("running")
        assertFalse(gate.reserveJob("still-full"))
        gate.finishJob("reserved")
        assertTrue(gate.reserveJob("replacement"))
        gate.releaseTerminal("pty1")
        assertTrue(gate.reserveTerminal("new-pty"))
    }

    @Test fun concurrentRequestsForLastTerminalSlotAdmitExactlyOneAndReleaseAllowsNext() {
        val capacity = RuntimeExecutionCapacity()
        repeat(3) { assertTrue(capacity.reserveTerminal("existing-$it")) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts =
                (0..15).map { id ->
                    pool.submit<Boolean> {
                        check(start.await(5, TimeUnit.SECONDS))
                        capacity.reserveTerminal("candidate-$id")
                    }
                }
            start.countDown()
            val results = attempts.map { it.get(5, TimeUnit.SECONDS) }
            org.junit.Assert.assertEquals(1, results.count { it })
            capacity.releaseTerminal("candidate-${results.indexOf(true)}")
            assertTrue(capacity.reserveTerminal("after-exit"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun pressureRestrictsNewTerminalsWithoutReleasingExistingExecutors() {
        val gate = RuntimeExecutionCapacity()
        assertTrue(gate.reserveTerminal("first", 1))
        assertFalse(gate.reserveTerminal("pressure", 1))
        assertTrue(gate.reserveTerminal("healthy", 4))
        assertFalse(gate.reserveTerminal("pressure-again", 1))
        assertFalse(gate.reserveMaintenance("still-in-use"))
        gate.releaseTerminal("first")
        assertFalse(gate.reserveTerminal("one-still-running", 1))
        gate.releaseTerminal("healthy")
        assertTrue(gate.reserveTerminal("after-exit", 1))
    }

    @Test fun executionIdentityCannotOccupyBothKindsOrBeReservedTwice() {
        val gate = RuntimeExecutionCapacity()
        assertTrue(gate.reserveTerminal("pty"))
        assertFalse(gate.reserveTerminal("pty"))
        assertFalse(gate.reserveJob("pty"))
        assertTrue(gate.reserveJob("job"))
        assertFalse(gate.reserveTerminal("job"))
    }

    @Test fun mixedConcurrentAdmissionKeepsBothClassAndAggregateBounds() {
        val gate = RuntimeExecutionCapacity()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val attempts =
                (0..31).map { id ->
                    pool.submit<Boolean> {
                        check(start.await(5, TimeUnit.SECONDS))
                        if (id % 2 == 0) gate.reserveTerminal("pty$id") else gate.reserveJob("job$id")
                    }
                }
            start.countDown()
            val accepted = attempts.map { it.get(5, TimeUnit.SECONDS) }
            org.junit.Assert.assertEquals(6, accepted.count { it })
            assertTrue(accepted.filterIndexed { index, _ -> index % 2 == 0 }.count { it } <= 4)
            assertTrue(accepted.filterIndexed { index, _ -> index % 2 == 1 }.count { it } <= 4)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun foregroundOwnershipWaitsForPhysicalCleanupButOtherJobsContinue() {
        val gate = RuntimeExecutionCapacity(2)
        assertTrue(gate.reserveJob("finishing"))
        assertFalse(gate.isJobRunning("finishing"))
        assertTrue(gate.beginJob("finishing", true))
        // A terminal receipt or service reservation release is not executor exit.
        gate.releaseReservation("finishing")
        assertTrue(gate.isJobRunning("finishing"))
        assertTrue(gate.beginJob("independent", false))
        gate.finishJob("finishing")
        assertFalse(gate.isJobRunning("finishing"))
        assertTrue(gate.isJobRunning("independent"))
        assertTrue(gate.beginJob("replacement", false))
    }

    @Test fun terminalAndJobsCoexistAndResultsDoNotReserveCapacity() {
        val gate = RuntimeExecutionCapacity()
        assertTrue(gate.reserveTerminal("pty1"))
        assertTrue(gate.reserveTerminal("pty2"))
        repeat(4) { assertTrue(gate.beginJob("job$it", false)) }
        assertFalse(gate.beginJob("overflow", false))
        gate.finishJob("job0")
        assertTrue(gate.beginJob("next-with-uncollected-result", false))
    }

    @Test fun reservationAndPhysicalJobShareOneSlot() {
        val gate = RuntimeExecutionCapacity(1)
        assertTrue(gate.reserveJob("job"))
        assertTrue(gate.beginJob("job", true))
        gate.releaseReservation("job")
        assertFalse(gate.beginJob("other", false))
        assertFalse(gate.beginJob("job", true))
        gate.finishJob("job")
        assertTrue(gate.beginJob("other", false))
    }

    @Test fun failedReservationReleasesOnlyItself() {
        val gate = RuntimeExecutionCapacity(2)
        assertTrue(gate.reserveJob("a"))
        assertTrue(gate.reserveJob("b"))
        gate.releaseReservation("a")
        assertTrue(gate.reserveJob("c"))
        assertTrue(gate.beginJob("b", true))
        assertFalse(gate.reserveJob("b"))
    }

    @Test fun onlyEnvironmentMaintenanceExcludesUsersOfThatRuntime() {
        val gate = RuntimeExecutionCapacity()
        assertTrue(gate.reserveTerminal("pty"))
        assertFalse(gate.reserveMaintenance("repair"))
        gate.releaseTerminal("pty")
        assertTrue(gate.reserveJob("reserved"))
        assertFalse(gate.reserveMaintenance("repair"))
        gate.releaseReservation("reserved")
        assertTrue(gate.reserveMaintenance("repair"))
        assertTrue(gate.maintenanceActive)
        assertFalse(gate.reserveTerminal("pty"))
        assertFalse(gate.beginJob("job", false))
        gate.releaseMaintenance("wrong")
        assertFalse(gate.beginJob("job", false))
        gate.releaseMaintenance("repair")
        assertFalse(gate.maintenanceActive)
        assertTrue(gate.beginJob("job", false))
    }

    @Test fun concurrentAdmissionNeverExceedsPhysicalLimit() {
        val gate = RuntimeExecutionCapacity()
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts =
                (0..31).map { id ->
                    pool.submit {
                        check(start.await(5, TimeUnit.SECONDS))
                        if (gate.beginJob("job$id", false)) accepted.incrementAndGet()
                    }
                }
            start.countDown()
            attempts.forEach { it.get(5, TimeUnit.SECONDS) }
            org.junit.Assert.assertEquals(4, accepted.get())
        } finally {
            pool.shutdownNow()
        }
    }
}
