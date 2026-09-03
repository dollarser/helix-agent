package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HXA-051 limit defaults (doc 03 §4.1) and rejection of out-of-range values. The same
 * [JsExecutionLimits.validate] runs pre-bind on the client and defensively on the service.
 */
class JsExecutionLimitsTest {
    @Test
    fun defaultsMatchTheArchitectureTable() {
        val defaults = JsExecutionLimits.DEFAULTS
        assertEquals("wall time 10 s", 10_000L, defaults.timeoutMs)
        assertEquals("heap 64 MiB", 64L * 1024 * 1024, defaults.memoryBytes)
        assertEquals("source 256 KiB", 256 * 1024, defaults.maxSourceBytes)
        assertEquals("input 2 MiB", 2 * 1024 * 1024, defaults.maxInputBytes)
        assertEquals("output 256 KiB", 256 * 1024, defaults.maxOutputBytes)
        defaults.validate()
    }

    @Test
    fun constructorDefaultsMatchExplicitDefaults() {
        assertEquals(JsExecutionLimits.DEFAULTS, JsExecutionLimits())
    }

    @Test
    fun invalidValuesAreRejected() {
        val invalid =
            listOf(
                JsExecutionLimits(timeoutMs = 0),
                JsExecutionLimits(timeoutMs = 99),
                JsExecutionLimits(timeoutMs = 30_001),
                JsExecutionLimits(memoryBytes = 0),
                JsExecutionLimits(memoryBytes = -1),
                JsExecutionLimits(memoryBytes = 2L * 1024 * 1024 * 1024),
                JsExecutionLimits(maxSourceBytes = 0),
                JsExecutionLimits(maxSourceBytes = 2 * 1024 * 1024),
                JsExecutionLimits(maxInputBytes = 0),
                JsExecutionLimits(maxInputBytes = 64 * 1024 * 1024),
                JsExecutionLimits(maxOutputBytes = 0),
                JsExecutionLimits(maxOutputBytes = 4 * 1024 * 1024),
            )
        for (limits in invalid) {
            try {
                limits.validate()
                fail("expected rejection for $limits")
            } catch (e: IllegalArgumentException) {
                assertTrue("rejection reason must be stable, got ${e.message}", !e.message.isNullOrBlank())
            }
        }
    }

    @Test
    fun boundaryValuesAreAccepted() {
        JsExecutionLimits(timeoutMs = 100).validate()
        JsExecutionLimits(timeoutMs = 30_000).validate()
        JsExecutionLimits(memoryBytes = 1L * 1024 * 1024).validate()
        JsExecutionLimits(memoryBytes = 1L * 1024 * 1024 * 1024).validate()
        JsExecutionLimits(maxSourceBytes = 1).validate()
        JsExecutionLimits(maxSourceBytes = 1024 * 1024).validate()
        JsExecutionLimits(maxInputBytes = 1).validate()
        JsExecutionLimits(maxInputBytes = 32 * 1024 * 1024).validate()
        JsExecutionLimits(maxOutputBytes = 1).validate()
        JsExecutionLimits(maxOutputBytes = 2 * 1024 * 1024).validate()
    }

    @Test
    fun valuesBelowDefaultsAreAccepted() {
        // §4.1: user settings may lower the defaults.
        JsExecutionLimits(
            timeoutMs = 5_000,
            memoryBytes = 32L * 1024 * 1024,
            maxSourceBytes = 128 * 1024,
            maxInputBytes = 1 * 1024 * 1024,
            maxOutputBytes = 128 * 1024,
        ).validate()
    }

    @Test
    fun valuesAboveCapsAreRejected() {
        // The caps are the protocol's hard ceiling; "the model cannot raise a default"
        // (doc 03 §4.1) is enforced at the policy layer (HXA-053), not by range
        // validation — values between default and cap are protocol-legal.
        val overCap =
            listOf(
                { JsExecutionLimits(timeoutMs = 30_001) },
                { JsExecutionLimits(maxSourceBytes = 1024 * 1024 + 1) },
                { JsExecutionLimits(maxInputBytes = 32 * 1024 * 1024 + 1) },
                { JsExecutionLimits(maxOutputBytes = 2 * 1024 * 1024 + 1) },
            )
        overCap.forEach { factory ->
            try {
                factory().validate()
                fail("value above the cap must be rejected")
            } catch (e: IllegalArgumentException) {
                assertTrue("rejection reason must be stable, got ${e.message}", !e.message.isNullOrBlank())
            }
        }
    }
}
