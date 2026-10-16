package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PluginPerformanceDiagnosticsTest {
    @Test
    fun preservesResultsExceptionsAndInclusiveMonotonicMeasurements() {
        var clock = 0L
        val diagnostics = PluginPerformanceDiagnostics { clock }
        val answer =
            diagnostics.measure("browser", PluginMeasurement.READINESS) {
                clock = 5
                false
            }
        assertFalse(answer)
        val failure = IllegalStateException("secret exception text")
        assertEquals(
            failure,
            assertThrows(IllegalStateException::class.java) {
                diagnostics.measure("browser", PluginMeasurement.READINESS) {
                    clock = 12
                    throw failure
                }
            },
        )
        val row = diagnostics.snapshot().single()
        assertEquals(2L, row.calls)
        assertEquals(1L, row.failures)
        assertEquals(12L, row.totalNanos)
        assertEquals(7L, row.maxNanos)
        assertFalse(diagnostics.json().toString().contains("secret"))
    }

    @Test
    fun concurrentUpdatesAreCountedAndOldSnapshotsRemainImmutable() {
        val clock = AtomicLong()
        val diagnostics = PluginPerformanceDiagnostics { clock.incrementAndGet() }
        val pool = Executors.newFixedThreadPool(4)
        val before = diagnostics.snapshot()
        try {
            val work =
                (1..100).map {
                    pool.submit { diagnostics.measure("documents", PluginMeasurement.FINGERPRINT) { "result" } }
                }
            work.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(100L, diagnostics.snapshot().single().calls)
            assertTrue(before.isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun unknownIdentifiersAndRepeatedNewKeysCannotGrowEvidenceWithoutBound() {
        val diagnostics = PluginPerformanceDiagnostics()
        assertEquals(7, diagnostics.measure("invalid/private input", PluginMeasurement.READINESS) { 7 })
        assertTrue(diagnostics.snapshot().isEmpty())
        repeat(100) { diagnostics.measure("plugin-$it", PluginMeasurement.REGISTRATION) { true } }
        assertTrue(diagnostics.snapshot().size <= 32)
        assertTrue(
            diagnostics
                .json()
                .toString()
                .toByteArray()
                .size < 12 * 1024,
        )
    }
}
