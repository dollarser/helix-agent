package com.helix.runtime.quickjs

import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * HXA-050 spike capability 5 (roadmap M5 + doc 03 §4.4): the relationship between the
 * QuickJS `maxStackSize` JS-level limit and the executing thread's NATIVE stack, and
 * that a calling thread with a stack GREATER than 6 MiB runs deep JS recursion that
 * fails as a JS error rather than a process crash.
 *
 * Probe findings on API 29 + API 36 arm64-v8a (pinned):
 * - default `maxStackSize` is 524288 (512 KiB); deep recursion fails with
 *   `QuickJsException: "stack overflow"` and the instance survives;
 * - 16 MiB explicit-stack threads are creatable (bionic honors the size; the 8 MiB
 *   soft RLIMIT_STACK does NOT cap explicit thread stacks on these builds);
 * - a `QuickJs` instance's stack baseline is captured on the CREATING thread: creating
 *   it on one thread and evaluating on another fails immediately with "stack overflow"
 *   (even at compile time). HXA-051 MUST create the instance on its execution thread.
 *
 * The [Timeout] rule is the anti-hang guard for the unbounded-recursion sources.
 */
class QuickJsStackSpikeTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(180)

    private lateinit var quickJs: QuickJs

    @Before
    fun createInstance() {
        quickJs = QuickJs.create()
    }

    @After
    fun closeInstance() {
        quickJs.close()
    }

    private companion object {
        const val DEEP_RECURSION = "function r(n) { return 1 + r(n - 1); } r(100000000)"
        const val DEFAULT_MAX_STACK_SIZE = 512L * 1024L
        const val BIG_THREAD_STACK_BYTES = 16L * 1024 * 1024
        const val EIGHT_MIB_JS_LIMIT_BYTES = 8L * 1024 * 1024
    }

    @Test
    fun defaultStackLimitFailsDeepRecursionAsJsError() {
        // Pinned: Zipline's JNI default maxStackSize (QuickJsNativeLoader path).
        assertEquals(DEFAULT_MAX_STACK_SIZE, quickJs.maxStackSize)
        val error =
            runCatching { quickJs.evaluate(DEEP_RECURSION) }.exceptionOrNull()
                ?: throw AssertionError("expected stack-overflow error")
        assertTrue("expected QuickJsException, got ${error.javaClass.name}", error is QuickJsException)
        assertTrue(
            "expected 'stack overflow', got: ${error.message?.take(160)}",
            error.message?.contains("stack overflow") == true,
        )
        // Pinned: the instance survives a stack-overflow error.
        assertEquals(6, quickJs.evaluate("3 + 3"))
    }

    @Test
    fun sixteenMiBCallingThreadSupportsEightMiBJsStackWithoutCrash() {
        assertThreadStartable(BIG_THREAD_STACK_BYTES)
        val executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(null, r, "quickjs-spike-big", BIG_THREAD_STACK_BYTES).apply { isDaemon = true }
            }
        try {
            // The instance is created ON the big-stack thread (thread-affinity finding).
            val result: Future<String> =
                executor.submit(
                    Callable {
                        val js = QuickJs.create()
                        try {
                            js.maxStackSize = EIGHT_MIB_JS_LIMIT_BYTES
                            val outcome =
                                runCatching { js.evaluate(DEEP_RECURSION) }
                                    .exceptionOrNull()
                                    ?.let { "error: ${it.javaClass.name}: ${it.message?.take(120)}" }
                                    ?: "NO ERROR"
                            val after = runCatching { js.evaluate("4 + 4") }.toString()
                            "$outcome | after: $after"
                        } finally {
                            js.close()
                        }
                    },
                )
            val observation = result.get(120, TimeUnit.SECONDS)
            // Pinned property: with an 8 MiB JS limit (above the 6 MiB architecture
            // floor) on a 16 MiB native stack, deep recursion fails as a JS error —
            // the process is still alive to answer the follow-up evaluate.
            assertTrue(
                "expected a 'stack overflow' JS error, got: $observation",
                observation.contains("stack overflow"),
            )
            assertTrue(
                "expected the thread/instance to survive the overflow, got: $observation",
                observation.contains("after: Success(8)"),
            )
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun instanceIsBoundToItsCreatingThread() {
        // Pinned from the probe (exact same shape: create on the test thread, set the
        // 8 MiB JS limit on the test thread, evaluate on a 16 MiB thread) → the
        // stack baseline captured at create() makes the cross-thread evaluate fail
        // fast with a JS "stack overflow". Regression lock for HXA-051's
        // execution-thread design: the execution thread must own the instance.
        quickJs.maxStackSize = EIGHT_MIB_JS_LIMIT_BYTES
        val executor =
            Executors.newSingleThreadExecutor { r ->
                Thread(null, r, "quickjs-spike-xthread", BIG_THREAD_STACK_BYTES).apply { isDaemon = true }
            }
        try {
            val future: Future<String> =
                executor.submit(
                    Callable {
                        runCatching { quickJs.evaluate("1 + 1") }
                            .fold(
                                onSuccess = { "NO ERROR: $it" },
                                onFailure = { "error: ${it.javaClass.name}: ${it.message?.take(120)}" },
                            )
                    },
                )
            val observation = future.get(30, TimeUnit.SECONDS)
            assertTrue(
                "expected cross-thread evaluate to fail with a JS error, got: $observation",
                observation.startsWith("error: app.cash.zipline.QuickJsException"),
            )
            assertTrue("expected 'stack overflow', got: $observation", observation.contains("stack overflow"))
        } finally {
            executor.shutdown()
        }
        // Note: @After closes the instance on the creating (test) thread, which is
        // also where it must be used per the thread-affinity finding above.
    }

    private fun assertThreadStartable(stackBytes: Long) {
        val started = CountDownLatch(1)
        Thread(null, { started.countDown() }, "quickjs-spike-stackcheck", stackBytes).start()
        assertTrue(
            "a ${stackBytes / 1024 / 1024} MiB native-stack thread must be startable " +
                "(doc 03 §4.4: calling thread stack must exceed 6 MiB)",
            started.await(10, TimeUnit.SECONDS),
        )
    }
}
