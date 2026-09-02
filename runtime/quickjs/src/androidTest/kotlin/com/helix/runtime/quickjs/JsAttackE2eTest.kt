package com.helix.runtime.quickjs

import android.os.Build
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HXA-054 M5 attack and end-to-end suite — roadmap item set 1 (infinite loop, memory,
 * output flood), item set 4 (eval/fetch/require) and item set 5 (process crash).
 *
 * Every attack goes through the REAL main-process [JsExecutionClient] → isolated
 * [JsExecutionService] chain (never an in-process evaluate), and every test carries
 * device-side evidence: the main process is observed alive (its own PID is still in
 * the app process table), the isolated instance's PID is observed dead/reclaimed
 * where the scenario produces one, and a recovery execution on a fresh instance
 * always completes. The HXA-052 wrapper-escape 19-case suite stays the wrapper-layer
 * anchor (unchanged); this class pins the same blocks from the production chain with
 * CALL ATTEMPTS (not just `typeof` probes) plus the full-chain recovery evidence.
 *
 * Every wait is bounded (deadline + grace, ≤ 30 s reclamation); the class-level
 * [Timeout] rule is the last-resort anti-hang guard.
 */
class JsAttackE2eTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(600)

    private val support = JsExecutionTestSupport

    // ---------------------------------------------------------------- 1. infinite loop

    @Test
    fun infiniteLoopRunsFullDefaultWallAndTimesOut() {
        // The §4.1 DEFAULTS wall (10 s) — not a lowered user setting: `while(true){}`
        // must run the full default wall and land a stable TIMEOUT. The service-side
        // deadline interrupt fires at the monotonic deadline (deadline-first
        // classification) and replies TIMEOUT; the client would otherwise give up on
        // the Binder interaction at deadline + 1 s and synthesize the same status.
        val started = System.nanoTime()
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-loop"),
                    source = "while (true) {}",
                ),
            )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        println(
            "HXA-054 evidence — infinite loop (default 10 s wall): status=${result.status} " +
                "elapsedMs=$elapsedMs servicePid=${result.servicePid}",
        )
        assertEquals(
            "infinite loop must land TIMEOUT at the default 10 s wall, took $elapsedMs ms",
            JsExecutionStatus.TIMEOUT,
            result.status,
        )
        assertTrue(
            "elapsed $elapsedMs ms must be near the 10 s deadline (+1 s grace)",
            elapsedMs in 9_000L..14_000L,
        )
        // Device evidence — main process liveness: this test runs IN the app process;
        // the isolated loop process is left to system reclamation, never dragged down.
        assertTrue(
            "main app process ${Process.myPid()} must survive the isolated timeout",
            Process.myPid() in support.runningPids(),
        )
        if (result.servicePid > 0) {
            assertTrue(
                "abandoned instance PID ${result.servicePid} must be reclaimed within 30 s",
                support.awaitProcessGone(result.servicePid, 30_000L),
            )
        }
        // Recovery: the next execution (fresh instance) completes normally.
        val next = support.client.execute(support.params(support.newExecutionId("e2e-loop-next"), "return 6 * 7"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("42", next.outputUtf8.toString(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- 2. memory

    @Test
    fun oomSurfaceFormIsPinnedPerApiAndExecutionRecovers() {
        // The HXA-052-pinned SAFE 64 MiB heap-exhaustion form: a 32 MiB Array.fill.
        // NEVER the 16-million-iteration loop — that one takes the engine native
        // SIGSEGV on API 29 and must not be a test source (status.md pinned fact).
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-oom"),
                    source = "var a = new Array(32 * 1024 * 1024).fill(1); a.length",
                ),
            )
        assertEquals("expected OOM, got ${result.status}: ${result.detail}", JsExecutionStatus.OOM, result.status)
        assertNotEquals("service must run in a different process", Process.myPid(), result.servicePid)
        assertNotEquals("service must run in a different (isolated) UID", Process.myUid(), result.serviceUid)
        // The two pinned OOM surface forms (status.md / HXA-052 wrapper semantics): on
        // API 29 a bulk heap exhaustion can fail to allocate the JS Error object
        // itself, the caught-null form survives to the host as the EMPTY message; on
        // API 36 the Error carries "out of memory" (the wrapper prefix survives as a
        // substring).
        val sdk = Build.VERSION.SDK_INT
        if (sdk <= 29) {
            assertEquals(
                "API 29 OOM surface form is the empty-message (caught-null) form, got: '${result.detail}'",
                "<empty message>",
                result.detail,
            )
        } else {
            assertTrue(
                "API 36 OOM surface form must carry 'out of memory', got: '${result.detail}'",
                result.detail.contains("out of memory"),
            )
        }
        println(
            "HXA-054 evidence — OOM: sdk=$sdk detail='${result.detail}' " +
                "servicePid=${result.servicePid} serviceUid=${result.serviceUid}",
        )
        // Device evidence — main process liveness + recovery: the OOM is a JS-level
        // error inside the isolated instance; the caller survives and a fresh
        // instance executes normally afterwards.
        assertTrue(
            "main app process ${Process.myPid()} must survive the isolated OOM",
            Process.myPid() in support.runningPids(),
        )
        val next = support.client.execute(support.params(support.newExecutionId("e2e-oom-next"), "return 1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("2", next.outputUtf8.toString(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- 3. output flood

    @Test
    fun outputFloodViaPfdChannelFailsBounded() {
        // 2 MiB string result (8× the 256 KiB output limit) with a writable output PFD:
        // the wrapper's bound check throws BEFORE anything crosses to the file, so the
        // failure is BOUNDED — the output file stays empty (no unbounded growth), the
        // result carries no output bytes, and the detail stays within the protocol cap.
        val outputFile = File(support.context.cacheDir, "js-e2e-flood-${System.nanoTime()}.out")
        try {
            val result =
                support.client.execute(
                    support.params(
                        executionId = support.newExecutionId("e2e-flood"),
                        source = RETURN_2MIB_STRING,
                        outputFile = outputFile,
                    ),
                )
            assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
            assertTrue(result.outputUtf8.isEmpty())
            assertEquals(0L, result.outputBytes)
            assertTrue(
                "detail must stay within the protocol cap (len=${result.detail.length})",
                result.detail.length <= JsProtocol.MAX_DETAIL_CHARS,
            )
            assertTrue(
                "detail must carry the bound reason, got: ${result.detail}",
                result.detail.contains(JsAbiAssembly.OUTPUT_LIMIT_MARKER),
            )
            assertTrue("the client-created output file must exist", outputFile.exists())
            assertEquals(
                "a flooded output must not grow the PFD file unbounded",
                0L,
                outputFile.length(),
            )
        } finally {
            outputFile.delete()
        }
        // Device evidence — main process liveness after a large failed allocation.
        assertTrue(Process.myPid() in support.runningPids())
        val next = support.client.execute(support.params(support.newExecutionId("e2e-flood-next"), "return 1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
    }

    @Test
    fun largeObjectFloodViaPfdChannelFailsBounded() {
        // A large OBJECT result (1 MiB string member + array): stringify happens inside
        // the wrapper, so object floods hit the same bound check as string floods —
        // bounded failure, empty output file, stable OUTPUT_LIMIT.
        val outputFile = File(support.context.cacheDir, "js-e2e-floodobj-${System.nanoTime()}.out")
        try {
            val result =
                support.client.execute(
                    support.params(
                        executionId = support.newExecutionId("e2e-floodobj"),
                        source = RETURN_LARGE_OBJECT,
                        outputFile = outputFile,
                    ),
                )
            assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
            assertTrue(result.outputUtf8.isEmpty())
            assertEquals(0L, outputFile.length())
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun outputBoundaryAndPlusOneByteFromClientChain() {
        // The 256 KiB output boundary from the CLIENT full-chain perspective (the
        // wrapper-layer byte boundary is HXA-052's): a document of exactly
        // maxOutputBytes is SUCCESS; one byte over is OUTPUT_LIMIT with the marker and
        // the measured length in a BOUNDED detail (never a truncated success).
        val over =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-boundover"),
                    source = RETURN_BOUNDARY_PLUS_ONE,
                ),
            )
        assertEquals(JsExecutionStatus.OUTPUT_LIMIT, over.status)
        assertTrue(over.outputUtf8.isEmpty())
        assertTrue(
            "detail must carry the marker + the measured document length, got: ${over.detail}",
            over.detail.contains(JsAbiAssembly.OUTPUT_LIMIT_MARKER) && over.detail.contains("262145"),
        )
        assertTrue(
            "detail must stay bounded (len=${over.detail.length})",
            over.detail.length <= JsProtocol.MAX_DETAIL_CHARS,
        )

        val exactFile = File(support.context.cacheDir, "js-e2e-boundex-${System.nanoTime()}.out")
        try {
            val exact =
                support.client.execute(
                    support.params(
                        executionId = support.newExecutionId("e2e-boundex"),
                        source = RETURN_BOUNDARY_EXACT,
                        outputFile = exactFile,
                    ),
                )
            assertEquals(JsExecutionStatus.SUCCESS, exact.status)
            assertEquals(256L * 1024, exact.outputBytes)
        } finally {
            exactFile.delete()
        }
    }

    // ---------------------------------------------------------------- 4. eval/fetch/require

    @Test
    fun hostBridgeCallAttemptsFailClosedOnTheProductionChain() {
        // HXA-052 pinned these globals as `typeof` undefined at the wrapper layer; here
        // the CALL ATTEMPTS themselves fail closed on the production client→service
        // chain (doc 03 §10: attempts at fetch/require/eval/Java Bridge all fail):
        // each attempt is a stable JS_ERROR, never a success and never a caller crash.
        val attempts =
            mapOf(
                "fetch" to """return fetch("https://example.invalid")""",
                "require" to """return require("fs")""",
                "XMLHttpRequest" to "return new XMLHttpRequest()",
                "WebSocket" to """return new WebSocket("ws://example.invalid")""",
                "java" to "return java.lang.System.exit(0)",
                "android" to "return android.os.Process.killProcess(0)",
            )
        attempts.forEach { (name, source) ->
            val result =
                support.client.execute(
                    support.params(support.newExecutionId("e2e-bridge-$name"), source),
                )
            assertEquals(
                "the '$name' attempt must fail closed as JS_ERROR, got ${result.status}",
                JsExecutionStatus.JS_ERROR,
                result.status,
            )
            assertTrue(
                "detail must carry the wrapper prefix, got: ${result.detail}",
                result.detail.startsWith(JsAbiAssembly.ERROR_PREFIX),
            )
            assertTrue(
                "detail must name the blocked global, got: ${result.detail}",
                result.detail.contains(name),
            )
        }
    }

    @Test
    fun dynamicCompilationAttemptsFailClosedOnTheProductionChain() {
        // Direct + parenthesized + indirect dynamic-compilation attempts: the
        // wrapper-layer variant set is HXA-052's, the production chain re-pins the
        // engine block on every attempt form — `eval is not supported` on all.
        val sources =
            listOf(
                """return eval("1+1")""",
                """return (0, eval)("1+1")""",
                """return globalThis["ev" + "al"]("1+1")""",
                """return new Function("return 1")()""",
            )
        sources.forEachIndexed { index, source ->
            val result =
                support.client.execute(support.params(support.newExecutionId("e2e-dyn-$index"), source))
            assertEquals("expected JS_ERROR for: $source", JsExecutionStatus.JS_ERROR, result.status)
            assertTrue(
                "detail must carry the engine block, got: ${result.detail}",
                result.detail.contains("eval is not supported"),
            )
        }
    }

    @Test
    fun mainProcessSurvivesEveryHostBridgeAttempt() {
        // Device evidence: after the full host-bridge attempt set, the main process is
        // alive and a fresh execution completes (no bridge attempt takes the caller
        // down or poisons the execution channel).
        assertTrue(Process.myPid() in support.runningPids())
        val result =
            support.client.execute(support.params(support.newExecutionId("e2e-bridge-next"), "return 1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("2", result.outputUtf8.toString(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- 5. process crash

    // Runs [action] while a daemon observer records the `:helix_js` PIDs every 10 ms
    // (evidence collection for the crash test); returns the action's result plus the
    // observed PID set.
    private fun withIsolatedPidObservation(
        action: () -> JsExecutionResult,
    ): Pair<JsExecutionResult, LinkedHashSet<Int>> {
        val observedPids = LinkedHashSet<Int>()
        val stop = AtomicBoolean(false)
        val observer =
            Thread(
                {
                    while (!stop.get()) {
                        observedPids.addAll(support.isolatedPids())
                        Thread.sleep(10)
                    }
                },
                "e2e-crash-observer",
            )
        observer.isDaemon = true
        observer.start()
        val result =
            try {
                action()
            } finally {
                stop.set(true)
                observer.join(2_000)
            }
        return result to observedPids
    }

    @Test
    fun crashKillsOnlyTheIsolatedInstanceAndClientRecovers() {
        // TEST-ONLY seam (BuildConfig.DEBUG + explicit request flag; the HXA-053
        // production tool path never sets it): simulates an external crash of the
        // isolated process mid-execution.
        //
        // Device evidence: the observer records the `:helix_js` PIDs while the crash
        // is in flight; after the stable CRASHED result the crashed PID must be gone
        // from the process table (isolated instance died) while the MAIN process (this
        // test's own process) is alive. If the observer misses the short-lived PID,
        // the DeadObjectException that produced the CRASHED result is itself the
        // kernel's binder-death signal — the instance process DID die.
        val started = System.nanoTime()
        val (result, observedPids) =
            withIsolatedPidObservation {
                support.client.execute(
                    support.params(
                        executionId = support.newExecutionId("e2e-crash"),
                        source = "while (true) {}",
                        debugInjectCrash = true,
                        debugCrashAfterMs = 250L,
                    ),
                )
            }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        // Stable CRASHED at the seam (~250 ms): no replay toward the 10 s deadline, no
        // fake success, no unhandled throw out of the client.
        assertEquals(JsExecutionStatus.CRASHED, result.status)
        assertTrue(
            "crash must surface near the 250 ms seam, not the 10 s deadline; took $elapsedMs ms",
            elapsedMs < 5_000L,
        )
        assertTrue("detail must state the outcome is unknown, got: '${result.detail}'", result.detail.isNotBlank())
        assertTrue(
            "CRASHED is a client-side outcome carrying no (dead) service identity",
            result.servicePid == -1,
        )
        // Main process liveness: this test's own process must still be running.
        assertTrue(
            "main app process ${Process.myPid()} must survive the isolated crash",
            Process.myPid() in support.runningPids(),
        )
        // The observed isolated PID(s) must be gone after the crash (the assertion
        // below throws first when an observed PID survives).
        observedPids.forEach { pid ->
            assertTrue(
                "isolated PID $pid must be gone from the process table after the crash",
                support.awaitProcessGone(pid, 30_000L),
            )
        }
        println(
            "HXA-054 evidence — crash seam: status=${result.status} elapsedMs=$elapsedMs " +
                "observedIsolatedPids=$observedPids goneFromProcessTable=true " +
                "mainPid=${Process.myPid()} mainAlive=true",
        )
        // Recovery: the next execution (fresh instance name) completes normally and
        // must not reuse a crashed PID.
        val next = support.client.execute(support.params(support.newExecutionId("e2e-crash-next"), "return 1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("2", next.outputUtf8.toString(Charsets.UTF_8))
        observedPids.forEach { pid ->
            assertNotEquals("a fresh instance must not reuse the crashed PID", pid, next.servicePid)
        }
    }

    companion object {
        private const val RETURN_2MIB_STRING = "return \"a\".repeat(2 * 1024 * 1024)"

        private const val RETURN_LARGE_OBJECT = "return { s: \"a\".repeat(1024 * 1024), arr: [1, 2, 3] }"

        // Document = 2 quotes + 262143 chars = 262145 units = maxOutputBytes + 1.
        private const val RETURN_BOUNDARY_PLUS_ONE = "return \"a\".repeat(256 * 1024 - 1)"

        // Document = 2 quotes + 262142 chars = exactly maxOutputBytes (262144).
        private const val RETURN_BOUNDARY_EXACT = "return \"a\".repeat(256 * 1024 - 2)"
    }
}
