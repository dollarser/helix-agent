package com.helix.runtime.quickjs

import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HXA-050 spike capability 2 (roadmap M5 + doc 03 §10): `memoryLimit` turns oversized
 * JS allocation into a JS-level error without killing the process, and the instance
 * stays usable afterwards (or can be closed cleanly).
 *
 * Probe observations (API 29 + API 36 arm64-v8a): both limits fail with
 * `app.cash.zipline.QuickJsException: out of memory`; the same instance then evaluates
 * `6 * 7` to 42. Pinned here as regression locks.
 */
class QuickJsMemoryLimitSpikeTest {
    private lateinit var quickJs: QuickJs

    @Before
    fun createInstance() {
        quickJs = QuickJs.create()
    }

    @After
    fun closeInstance() {
        quickJs.close()
    }

    @Test
    fun twoMiBLimitTurnsOversizedAllocationIntoJsError() {
        quickJs.memoryLimit = 2L * 1024 * 1024
        assertOutOfMemory(
            "var a = new Array(4 * 1024 * 1024); for (var i = 0; i < a.length; i++) a[i] = i; a.length",
        )
        assertInstanceSurvives()
    }

    @Test
    fun defaultSixtyFourMiBLimitTurnsOversizedAllocationIntoJsError() {
        quickJs.memoryLimit = 64L * 1024 * 1024
        assertOutOfMemory("var a = new Array(32 * 1024 * 1024).fill(1); a.length")
        assertInstanceSurvives()
    }

    @Test
    fun memoryUsageTracksAllocations() {
        // doc 03 §4: memoryUsage is the engine's own accounting surface (HXA-051/052
        // will expose it in the execution result).
        quickJs.evaluate("var a = new Array(64 * 1024).fill(1); a.length")
        assertTrue(
            "memoryUsedSize must be > 0 after allocating a 64 KiB array, " +
                "but was ${quickJs.memoryUsage.memoryUsedSize}",
            quickJs.memoryUsage.memoryUsedSize > 0,
        )
    }

    private fun assertOutOfMemory(source: String) {
        val error =
            runCatching { quickJs.evaluate(source) }.exceptionOrNull()
                ?: throw AssertionError("expected out-of-memory error for: $source")
        assertTrue(
            "expected QuickJsException, got ${error.javaClass.name}",
            error is QuickJsException,
        )
        // Pinned observation: the message is normally "out of memory", but when the
        // 64 MiB heap is exhausted on API 29 the Error object allocation itself can
        // fail and the same JS-level OOM surfaces with an EMPTY message. Both are the
        // expected JS-error failure form (never a process crash).
        val message = error.message
        assertTrue(
            "expected an empty or 'out of memory' error message, got: ${message?.take(160)}",
            message.isNullOrEmpty() || message.contains("out of memory"),
        )
    }

    private fun assertInstanceSurvives() {
        // Probe observation: the SAME instance keeps working after the OOM error.
        assertEquals(42, quickJs.evaluate("6 * 7"))
    }
}
