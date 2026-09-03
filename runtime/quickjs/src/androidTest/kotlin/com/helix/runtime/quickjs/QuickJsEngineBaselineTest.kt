package com.helix.runtime.quickjs

import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * HXA-051 in-process engine baseline (ADR-0015 boundary).
 *
 * This test class runs INSIDE the app process (no isolated service) and pins the
 * deterministic engine facts the protocol relies on:
 *
 * - the Zipline QuickJS engine version is pinned;
 * - a `QuickJs` instance's create/evaluate/close on ONE thread is the supported
 *   lifecycle: same-thread deep JS recursion degrades to a recoverable JS-level
 *   `stack overflow` (never a process crash) and the instance survives. This is the
 *   property the service's dedicated execution thread (16 MiB stack) depends on.
 *
 * Cross-thread note (why ADR-0015 mandates one thread): the engine captures the JS
 * stack baseline on the CREATING thread. Evaluating from ANOTHER thread is undefined
 * behavior whose outcome depends on the two threads' relative stack addresses (ASLR):
 * observed in this task as a spurious `stack overflow` on one device and a silent
 * SUCCESS on the other for the same trivial source. Because the outcome is not a stable
 * contract, it is deliberately NOT asserted here — the service simply never evaluates
 * off its execution thread, and the same-thread degradation above is what is pinned.
 */
class QuickJsEngineBaselineTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(120)

    @Test
    fun engineVersionIsPinned() {
        // HXA-050 pinned the engine version; HXA-051's protocol assumes its behavior.
        assertEquals("2021-03-27", QuickJs.version)
    }

    @Test
    fun sameThreadDeepRecursionDegradesToJsErrorNotCrash() {
        // The core ADR-0015 property the execution-thread design relies on: with the
        // instance owned by its executing thread, unbounded JS recursion fails as a
        // JS-level `stack overflow` (recoverable, classified) — never a native crash —
        // and the same instance is usable afterwards. Pinned in-process so the boundary
        // is anchored even though it is not observable through the IPC protocol.
        val js = QuickJs.create()
        try {
            val error =
                runCatching { js.evaluate(DEEP_RECURSION, "deep.js") }.exceptionOrNull()
                    ?: throw AssertionError("expected a JS stack-overflow error")
            assertTrue(
                "expected QuickJsException, got ${error.javaClass.name}",
                error is QuickJsException,
            )
            assertTrue(
                "expected the engine 'stack overflow' form, got: ${error.message}",
                (error as QuickJsException).message.orEmpty().contains("stack overflow"),
            )
            // Pinned: the instance survives a same-thread stack overflow.
            assertJsNumber(6, js.evaluate("3 + 3", "after.js"))
        } finally {
            js.close()
        }
    }

    @Test
    fun sameThreadReentrantLifecycleIsStable() {
        // Create → evaluate → close → recreate on the same thread stays stable (the
        // service runs one create/evaluate/close per instance on its execution thread).
        repeat(3) {
            val js = QuickJs.create()
            try {
                assertJsNumber(7, js.evaluate("1 + 6", "loop.js"))
            } finally {
                js.close()
            }
        }
    }

    private fun assertJsNumber(
        expected: Int,
        actual: Any?,
    ) {
        assertTrue("JS number expected, got: $actual", actual is Number)
        assertEquals(expected.toDouble(), (actual as Number).toDouble(), 0.0)
    }

    private companion object {
        const val DEEP_RECURSION = "function r(n) { return 1 + r(n - 1); } r(100000000)"
    }
}
