package com.helix.runtime.quickjs

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.nio.charset.StandardCharsets

/**
 * HXA-051 execution protocol over the isolated service (doc 03 §3/§4): round trips,
 * limit rejections and the stable error classification — every execution through a fresh
 * unique instance, never retried.
 */
class JsExecutionProtocolTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(240)

    private val support = JsExecutionTestSupport

    @Test
    fun smallParcelRoundTripSucceeds() {
        val result = support.client.execute(support.params(support.newExecutionId("roundtrip"), "1 + 2 * 3"))
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("7", result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Utf8("7"), result.outputSha256Hex)
        assertIsolated(result)
    }

    @Test
    fun unicodeStringRoundTripIsExact() {
        val text = "héllo 🚀 日本語"
        val result =
            support.client.execute(support.params(support.newExecutionId("unicode"), "\"$text\""))
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        // A top-level string result IS the JSON text: byte-for-byte pass-through.
        assertEquals(text, result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Utf8(text), result.outputSha256Hex)
    }

    @Test
    fun arrayResultIsJsonEncoded() {
        // Zipline converts a top-level JS array to a Kotlin List; the encoder then
        // serializes it to canonical JSON.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("array"), "[1, 2, 3]"),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("[1,2,3]", result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Utf8("[1,2,3]"), result.outputSha256Hex)
    }

    @Test
    fun topLevelObjectResultSurfacesAsNullInRawMode() {
        // Engine fact (HXA-051 raw-evaluate mode): Zipline's evaluate returns null for a
        // top-level JS object, so the protocol result is the JSON text "null" (SUCCESS).
        // This loss cannot happen in production mode: HXA-052's wrapper returns
        // JSON.stringify(...) — a string — before the value crosses the protocol.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("object"), "({ a: 1, b: [1, 2, 3], c: { d: true } })"),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("null", result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun emptyMessageFailureIsThePinnedApi29OomForm() {
        // HXA-050 pinned: a 64 MiB exhaustion on API 29 can surface the JS-level OOM
        // with an EMPTY message (the Error object itself cannot be allocated). That
        // message form classifies as OOM, not JS_ERROR — the contract is identical on
        // both APIs, so it is pinned here via the explicit empty-message form.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("oomform"), "throw new Error()"),
            )
        assertEquals(
            "empty-message failures label as the pinned OOM form, got ${result.status}",
            JsExecutionStatus.OOM,
            result.status,
        )
    }

    @Test
    fun thrownErrorIsJsError() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("jserror"), "throw new Error(\"boom\")"),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue("detail must carry the JS error text, got: ${result.detail}", result.detail.contains("boom"))
        assertIsolated(result)
    }

    @Test
    fun syntaxErrorIsJsError() {
        val result = support.client.execute(support.params(support.newExecutionId("syntax"), "1 +"))
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue("detail must be non-empty for a syntax error", result.detail.isNotBlank())
    }

    @Test
    fun deepRecursionIsJsErrorNotCrash() {
        val id = support.newExecutionId("recursion")
        val result =
            support.client.execute(
                support.params(id, "function r(n) { return r(n + 1); } r(0)"),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue(
            "detail must carry the engine stack-overflow form, got: ${result.detail}",
            result.detail.contains("stack overflow"),
        )
        // The engine-level stack failure never crashes the instance lifecycle: a later
        // execution (fresh instance) succeeds.
        val next = support.client.execute(support.params(support.newExecutionId("recursion-next"), "6 * 7"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("42", next.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun oomAtDefaultHeapIsStable() {
        // HXA-050-pinned: 64 MiB exhaustion is a JS-level OOM ("out of memory", or an
        // EMPTY message when the Error object itself cannot be allocated on API 29).
        val result =
            support.client.execute(
                support.params(
                    support.newExecutionId("oom64"),
                    "var a = new Array(32 * 1024 * 1024).fill(1); a.length",
                ),
            )
        assertEquals("expected OOM, got ${result.status}: ${result.detail}", JsExecutionStatus.OOM, result.status)
        assertIsolated(result)
        val next = support.client.execute(support.params(support.newExecutionId("oom64-next"), "1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
    }

    @Test
    fun oomAtLoweredHeapIsStable() {
        val limits = JsExecutionLimits(memoryBytes = 2L * 1024 * 1024)
        val result =
            support.client.execute(
                support.params(
                    support.newExecutionId("oom2"),
                    "var a = new Array(4 * 1024 * 1024); for (var i = 0; i < a.length; i++) a[i] = i; a.length",
                    limits = limits,
                ),
            )
        assertEquals("expected OOM, got ${result.status}: ${result.detail}", JsExecutionStatus.OOM, result.status)
    }

    @Test
    fun outputOverLimitIsRejected() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("outputlimit"), "\"a\".repeat(1024 * 1024 + 1)"),
            )
        assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
        assertTrue(result.outputUtf8.isEmpty())
    }

    @Test
    fun outputAboveInlineCapWithoutOutputPfdIsRejected() {
        // 100 KiB output: within the 256 KiB limit but above the 64 KiB inline parcel cap,
        // and no output PFD was provided → stable OUTPUT_LIMIT.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("inlinecap"), "\"a\".repeat(100 * 1024)"),
            )
        assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
    }

    @Test
    fun sourceOverLimitIsRejectedBeforeExecution() {
        val source = "1 + 1 // " + "pad".repeat(110_000) // ~330 KiB > 256 KiB default
        val result = support.client.execute(support.params(support.newExecutionId("srclimit"), source))
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
        assertEquals(-1, result.servicePid) // rejected pre-bind: no instance spawned
    }

    @Test
    fun inputOverLimitIsRejectedBeforeExecution() {
        val input = ByteArray(3 * 1024 * 1024) { ('a'.code + it % 26).toByte() }
        val result =
            support.client.execute(
                support.params(support.newExecutionId("inputlimit"), "1 + 1", inputJsonUtf8 = input),
            )
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
        assertEquals(-1, result.servicePid)
    }

    @Test
    fun invalidLimitsAreRejectedBeforeExecution() {
        val result =
            support.client.execute(
                support.params(
                    support.newExecutionId("badlimits"),
                    "1 + 1",
                    limits = JsExecutionLimits(timeoutMs = 0),
                ),
            )
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
        assertEquals(-1, result.servicePid)
    }

    @Test
    fun blankExecutionIdIsRejectedBeforeExecution() {
        val result = support.client.execute(support.params("", "1 + 1"))
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
    }

    @Test
    fun dynamicCompilationRemainsBlockedByEngine() {
        // ADR-0015 regression anchor through the production path: all dynamic-compilation
        // call paths throw the engine's `eval is not supported` (HXA-052 extends this
        // into the full wrapper-escape attack suite).
        val sources =
            listOf(
                "eval(\"1+1\")",
                "new Function(\"return 1\")()",
                "Object.constructor.constructor(\"return 1\")()",
            )
        sources.forEachIndexed { index, source ->
            val result =
                support.client.execute(support.params(support.newExecutionId("dyncompile-$index"), source))
            assertEquals("expected JS_ERROR for: $source", JsExecutionStatus.JS_ERROR, result.status)
            assertTrue(
                "detail must carry the engine block, got: ${result.detail}",
                result.detail.contains("eval is not supported"),
            )
        }
    }

    private fun assertIsolated(result: JsExecutionResult) {
        assertNotEquals("service must run in a different process", Process.myPid(), result.servicePid)
        assertNotEquals("service must run in a different (isolated) UID", Process.myUid(), result.serviceUid)
    }
}
