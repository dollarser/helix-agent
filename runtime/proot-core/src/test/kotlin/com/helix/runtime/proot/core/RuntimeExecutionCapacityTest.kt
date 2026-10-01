package com.helix.runtime.proot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeExecutionCapacityTest {
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
        assertFalse(gate.reserveTerminal("pty"))
        assertFalse(gate.beginJob("job", false))
        gate.releaseMaintenance("wrong")
        assertFalse(gate.beginJob("job", false))
        gate.releaseMaintenance("repair")
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
