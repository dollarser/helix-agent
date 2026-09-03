package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HXA-054 M5 attack and end-to-end suite — roadmap item 7 (cancel races).
 *
 * Concurrent threads create the three cancel races against the REAL client chain and
 * each round must converge to a stable terminal status from the HXA-051 closed set:
 *
 * - (a) cancel racing the cold/warm BIND → {CANCELLED, INTERRUPTED};
 * - (b) cancel racing an IN-FLIGHT execution → {CANCELLED, INTERRUPTED} (never
 *   SUCCESS — the cancel always lands while the execution is still active);
 * - (c) cancel arriving AFTER the result is ready → SUCCESS (a late cancel never
 *   clobbers a completed execution).
 *
 * The same execution is never executed twice: one [JsExecutionClient.execute] call
 * yields exactly one terminal result (no retry, no blind replay), and for a
 * service-answered INTERRUPTED round the one-shot server slot is probed directly —
 * a second EXECUTE on the SAME instance must be rejected `already used` (if the
 * instance was already reclaimed, the rebind is a FRESH instance with a new PID,
 * which is also not a replay of the same execution). 50 rounds per race raise the
 * hit probability; every wait is bounded (per-round deadline + grace), and the
 * class-level [Timeout] rule is the last-resort anti-hang guard.
 */
class JsCancelRaceE2eTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(900)

    private val support = JsExecutionTestSupport

    private val convergeSet = setOf(JsExecutionStatus.CANCELLED, JsExecutionStatus.INTERRUPTED)

    // A cancellation that flips after [delayMs], driven by a daemon thread (the race driver).
    private fun cancelAfter(delayMs: Long): JsCancellation {
        val flag = AtomicBoolean(false)
        val driver =
            Thread(
                {
                    try {
                        Thread.sleep(delayMs)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    flag.set(true)
                },
                "race-cancel-driver",
            )
        driver.isDaemon = true
        driver.start()
        return JsCancellation { flag.get() }
    }

    @Test
    fun cancelVsBindRaceConvergesInFiftyRounds() {
        // Race (a): a 30 ms cancel races the isolated instance's bind. If the bind
        // connects first (warm device) the cancel lands mid-execution → INTERRUPTED;
        // if it lands while the client still waits for the bind (cold device) the
        // execution never starts → CANCELLED. The 400 ms loop guarantees the cancel
        // always lands before the execution could finish — SUCCESS is impossible.
        val counts = mutableMapOf<JsExecutionStatus, Int>()
        repeat(50) { round ->
            val result =
                support.client.execute(
                    support.params(
                        executionId = support.newExecutionId("e2e-bindrace-$round"),
                        source = "var t0 = Date.now(); while (Date.now() - t0 < 400) {} 1",
                    ),
                    cancellation = cancelAfter(30),
                )
            assertTrue(
                "round $round: a cancel racing the bind must converge to CANCELLED/INTERRUPTED, got ${result.status}",
                result.status in convergeSet,
            )
            counts[result.status] = (counts[result.status] ?: 0) + 1
        }
        assertTrue(
            "only the convergence set may be observed across 50 rounds, got $counts",
            counts.isNotEmpty() && counts.keys.all { it in convergeSet },
        )
        println("HXA-054 evidence — cancel-vs-bind 50 rounds converged: $counts")
    }

    @Test
    fun cancelVsInFlightRaceConvergesInFiftyRounds() {
        // Race (b): a 100 ms cancel races a 3 s in-flight execution. The client
        // delivers the interrupt once; the engine halts at its next poll and the
        // service replies INTERRUPTED (deadline-first: the 10 s deadline is far, so
        // this is INTERRUPTED, not TIMEOUT). Exactly one terminal result per round —
        // the client never retries the same execution.
        val counts = mutableMapOf<JsExecutionStatus, Int>()
        var slotProbe: Pair<String, Int>? = null
        repeat(50) { round ->
            val executionId = support.newExecutionId("e2e-inflight-$round")
            val result =
                support.client.execute(
                    support.params(
                        executionId = executionId,
                        source = "var t0 = Date.now(); while (Date.now() - t0 < 3000) {} 1",
                    ),
                    cancellation = cancelAfter(100),
                )
            assertTrue(
                "round $round: a cancel racing an in-flight execution must converge to " +
                    "CANCELLED/INTERRUPTED (never SUCCESS), got ${result.status}",
                result.status in convergeSet,
            )
            counts[result.status] = (counts[result.status] ?: 0) + 1
            // Keep the first service-answered INTERRUPTED (carries the service PID) for
            // the one-shot-slot probe below.
            if (slotProbe == null && result.status == JsExecutionStatus.INTERRUPTED && result.servicePid > 0) {
                slotProbe = executionId to result.servicePid
            }
        }
        assertTrue(
            "only the convergence set may be observed across 50 rounds, got $counts",
            counts.isNotEmpty() && counts.keys.all { it in convergeSet },
        )
        println("HXA-054 evidence — cancel-vs-in-flight 50 rounds converged: $counts")

        // The same execution never runs twice: probe the one-shot slot of the first
        // service-answered INTERRUPTED instance. If the instance is still alive (same
        // PID), a second EXECUTE on it must be rejected `already used`; if it was
        // already reclaimed, the rebind is a fresh instance (new PID) — also not a
        // replay of the same execution on the same instance.
        slotProbe?.let { (executionId, oldPid) ->
            val bound = support.bindDirect(JsInstanceName.forExecution(executionId))
            try {
                if (bound.pid == oldPid) {
                    val second =
                        support.executeDirect(bound.binder, support.newExecutionId("e2e-slot"), "return 1 + 1")
                    assertEquals(
                        "a second execution on the same instance must be rejected",
                        JsExecutionStatus.REQUEST_REJECTED,
                        second.status,
                    )
                    assertTrue(
                        "the rejection must state the instance is used, got: ${second.detail}",
                        second.detail.contains("already used"),
                    )
                    println("HXA-054 evidence — one-shot slot re-exec on PID ${bound.pid} rejected: ${second.detail}")
                } else {
                    println(
                        "HXA-054 evidence — instance PID $oldPid already reclaimed; " +
                            "rebind of the same name is a fresh instance (PID ${bound.pid})",
                    )
                }
            } finally {
                bound.release()
            }
        }
    }

    @Test
    fun cancelArrivingAfterResultReadyKeepsSuccess() {
        // Race (c): the result is ready long before the 400 ms cancel lands. A late
        // cancel must never clobber a completed execution: every round stays SUCCESS.
        repeat(10) { round ->
            val result =
                support.client.execute(
                    support.params(
                        executionId = support.newExecutionId("e2e-latecancel-$round"),
                        source = "return 42",
                    ),
                    cancellation = cancelAfter(400),
                )
            assertEquals(
                "round $round: a cancel arriving after the result is ready must keep SUCCESS, got ${result.status}",
                JsExecutionStatus.SUCCESS,
                result.status,
            )
            assertEquals("42", result.outputUtf8.toString(Charsets.UTF_8))
        }
    }
}
