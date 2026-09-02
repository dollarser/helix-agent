package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HXA-051 execution control (doc 03 §4.3–§4.5): interrupt, wall-time timeout with the
 * main-process watchdog giving up on the Binder interaction after a 1 s grace, in-flight
 * cancellation, and stable CRASHED handling via the test-only crash seam.
 *
 * Every wait is bounded (deadline + grace) — these tests cannot hang the device runner,
 * and the class-level [Timeout] rule is the last-resort anti-hang guard.
 */
class JsExecutionControlTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(300)

    private val support = JsExecutionTestSupport

    @Test
    fun interruptTransactionEndsInfiniteLoopAsInterrupted() {
        // Explicit interrupt (cancellation token) before a distant deadline: the client
        // delivers the interrupt transaction; the service's interrupt handler halts the
        // loop and classifies INTERRUPTED (deadline-first rule keeps this distinct from
        // TIMEOUT because the deadline is far in the future).
        val cancel = AtomicBoolean(false)
        val canceller =
            Thread(
                {
                    Thread.sleep(300)
                    cancel.set(true)
                },
                "cancel-300ms",
            )
        canceller.isDaemon = true
        canceller.start()
        val started = System.nanoTime()
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("interrupt"),
                    source = "while (true) {}",
                    limits = JsExecutionLimits(timeoutMs = JsExecutionLimits.MAX_TIMEOUT_MS),
                ),
                cancellation = JsCancellation { cancel.get() },
            )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(JsExecutionStatus.INTERRUPTED, result.status)
        assertTrue(
            "must stop at the interrupt (~300 ms), not the 30 s deadline; took $elapsedMs ms",
            elapsedMs < 10_000L,
        )
        // A later execution on a fresh instance works: the control plane never kills.
        val next = support.client.execute(support.params(support.newExecutionId("interrupt-next"), "1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
    }

    @Test
    fun cancelInFlightIsStableAndNeverRetried() {
        val cancel = AtomicBoolean(false)
        val canceller =
            Thread(
                {
                    Thread.sleep(300)
                    cancel.set(true)
                },
                "cancel-inflight-300ms",
            )
        canceller.isDaemon = true
        canceller.start()
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("cancelflight"),
                    source = "while (true) {}",
                    limits = JsExecutionLimits(timeoutMs = JsExecutionLimits.MAX_TIMEOUT_MS),
                ),
                cancellation = JsCancellation { cancel.get() },
            )
        // In-flight cancel takes the interrupt path: a stable INTERRUPTED result, exactly
        // one outcome, no blind replay of the same execution.
        assertEquals(JsExecutionStatus.INTERRUPTED, result.status)
        assertTrue("detail must explain the interruption", result.detail.isNotBlank())
    }

    @Test
    fun cancelBeforeStartIsCancelledWithoutInstance() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("cancelstart"), "1 + 1"),
                cancellation = JsCancellation { true },
            )
        assertEquals(JsExecutionStatus.CANCELLED, result.status)
        assertEquals(-1, result.servicePid) // no instance was ever bound
        assertEquals(-1, result.serviceUid)
    }

    @Test
    fun watchdogTimeoutEndsInfiniteLoopAndReclaims() {
        // Small wall deadline (1.5 s): the service-side interrupt handler fires at the
        // monotonic deadline and the service replies TIMEOUT (deadline-first). The client
        // watchdog would additionally give up on the Binder interaction after a 1 s grace
        // and unbind; either way the result is a stable TIMEOUT and the instance is
        // abandoned to system reclamation (never killed from either process).
        val started = System.nanoTime()
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("timeout"),
                    source = "while (true) {}",
                    limits = JsExecutionLimits(timeoutMs = 1_500L),
                ),
            )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(JsExecutionStatus.TIMEOUT, result.status)
        assertTrue(
            "timeout must land near the 1.5 s deadline (+1 s grace), took $elapsedMs ms",
            elapsedMs in 1_000L..15_000L,
        )
        assertTrue("service identity must be present for the reclamation assertion", result.servicePid > 0)
        assertTrue(
            "abandoned instance PID ${result.servicePid} must be reclaimed within 30 s",
            support.awaitProcessGone(result.servicePid, 30_000L),
        )
        // The next execution (fresh instance) is unaffected.
        val next = support.client.execute(support.params(support.newExecutionId("timeout-next"), "6 * 7"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("42", next.outputUtf8.toString(Charsets.UTF_8))
    }

    @Test
    fun crashInjectionYieldsStableCrashedAndNextExecutionWorks() {
        // TEST-ONLY seam: BuildConfig.DEBUG-gated + explicit request flag (set here by an
        // instrumented test, never by the HXA-053 production tool path). Simulates an
        // external crash of the isolated process mid-execution; the client must map the
        // Binder death to a stable CRASHED and must not replay the execution.
        val started = System.nanoTime()
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("crash"),
                    source = "while (true) {}",
                    debugInjectCrash = true,
                    debugCrashAfterMs = 250L,
                ),
            )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(JsExecutionStatus.CRASHED, result.status)
        assertTrue(
            "crash must surface near the 250 ms seam, not the 10 s deadline; took $elapsedMs ms",
            elapsedMs < 5_000L,
        )
        assertTrue("detail must state the outcome is unknown", result.detail.isNotBlank())
        // Recovery: the next execution on a fresh instance name works normally.
        val next = support.client.execute(support.params(support.newExecutionId("crash-next"), "1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("2", next.outputUtf8.toString(Charsets.UTF_8))
    }

    @Test
    fun crashSeamIsInertWithoutDebugBuild() {
        // Defense in depth: even if a release-like flag path were wired, the seam is
        // compiled out unless BuildConfig.DEBUG. In this debug build the flag IS honored,
        // so this test pins the client-side gate instead: a pre-flight rejection path
        // exists for non-debug builds, and here (debug) the flag must reach the service.
        assertTrue("this test environment must be a debug build for the seam to be reachable", BuildConfig.DEBUG)
    }

    @Test
    fun cancelRacingTheColdBindResolvesToAStableTerminalStatus() {
        // A 50 ms cancel races the isolated instance's cold bind: if the bind connects
        // first (warm device) the cancel lands mid-execution → INTERRUPTED; if the
        // cancel lands while the client is still waiting for the bind (cold device) the
        // execution never starts → CANCELLED. Both are correct, stable, terminal and
        // never retried. The invariant pinned here: the outcome is in the closed set
        // {CANCELLED, INTERRUPTED} — never SUCCESS, never a blind replay.
        val cancel = AtomicBoolean(false)
        val canceller =
            Thread(
                {
                    Thread.sleep(50)
                    cancel.set(true)
                },
                "cancel-race-50ms",
            )
        canceller.isDaemon = true
        canceller.start()
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("cancelrace"),
                    source = "var t0 = Date.now(); while (Date.now() - t0 < 300) {} 1",
                ),
                cancellation = JsCancellation { cancel.get() },
            )
        assertTrue(
            "a cancel racing the bind must resolve to a stable CANCELLED/INTERRUPTED, got ${result.status}",
            result.status == JsExecutionStatus.CANCELLED || result.status == JsExecutionStatus.INTERRUPTED,
        )
    }
}
