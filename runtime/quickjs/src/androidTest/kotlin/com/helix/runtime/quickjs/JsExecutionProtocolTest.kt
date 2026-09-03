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
 * HXA-052 execution protocol over the isolated service (doc 03 §3/§4): round trips,
 * limit rejections and the stable error classification — every execution through a
 * fresh unique instance, never retried.
 *
 * HXA-052 ABI: the source is the helixMain BODY of the §3.2 wrapper; on SUCCESS the
 * output is the wrapper's JSON.stringify text (a JSON document). The HXA-051
 * raw-mode-specific assertions (top-level object → null, empty-message OOM form)
 * are replaced by their wrapper semantics — see the individual tests.
 */
class JsExecutionProtocolTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(240)

    private val support = JsExecutionTestSupport

    @Test
    fun smallParcelRoundTripSucceeds() {
        val result = support.client.execute(support.params(support.newExecutionId("roundtrip"), "return 1 + 2 * 3"))
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("7", result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Utf8("7"), result.outputSha256Hex)
        assertIsolated(result)
    }

    @Test
    fun unicodeStringRoundTripIsExact() {
        val text = "héllo 🚀 日本語"
        val result =
            support.client.execute(support.params(support.newExecutionId("unicode"), """return "$text""""))
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        // Wrapper result = JSON.stringify(text): the JSON string document, quoted.
        val expected = """"$text""""
        assertEquals(expected, result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Utf8(expected), result.outputSha256Hex)
    }

    @Test
    fun arrayResultIsJsonEncoded() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("array"), "return [1, 2, 3]"),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("[1,2,3]", result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Utf8("[1,2,3]"), result.outputSha256Hex)
    }

    @Test
    fun topLevelObjectResultIsStringifiedNotLost() {
        // HXA-051 raw mode: a top-level JS object crossed the protocol as null (loss).
        // HXA-052 wrapper: the object is JSON.stringify'd inside the wrapper BEFORE it
        // crosses the protocol — the round trip is loss-free.
        val result =
            support.client.execute(
                support.params(
                    support.newExecutionId("object"),
                    "return { a: 1, b: [1, 2, 3], c: { d: true } }",
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("""{"a":1,"b":[1,2,3],"c":{"d":true}}""", result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun emptyMessageThrowIsJsErrorWithNonBlankDetail() {
        // HXA-051 raw mode: `throw new Error()` surfaced an EMPTY message and was
        // classified by the pinned OOM form (an acknowledged ambiguity). HXA-052
        // wrapper: user errors are rethrown as NON-BLANK prefixed strings — the
        // ambiguity is eliminated and the case is a plain JS error.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("emptythrow"), """throw new Error("")"""),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue("detail must be non-blank, got: '${result.detail}'", result.detail.isNotBlank())
        assertTrue(
            "detail must carry the wrapper prefix, got: ${result.detail}",
            result.detail.startsWith(JsAbiAssembly.ERROR_PREFIX),
        )
    }

    @Test
    fun thrownErrorIsJsError() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("boom"), """throw new Error("boom")"""),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue(
            "detail must carry the wrapper prefix + the message, got: ${result.detail}",
            result.detail.contains(JsAbiAssembly.ERROR_PREFIX) && result.detail.contains("boom"),
        )
    }

    @Test
    fun syntaxErrorIsJsError() {
        val result = support.client.execute(support.params(support.newExecutionId("syntax"), "return 1 +"))
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue("detail must be non-empty for a syntax error", result.detail.isNotBlank())
    }

    @Test
    fun deepRecursionIsJsErrorNotCrash() {
        val id = support.newExecutionId("recursion")
        val result =
            support.client.execute(
                support.params(id, "function r(n) { return r(n + 1); } return r(0)"),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue(
            "detail must carry the engine stack-overflow form, got: ${result.detail}",
            result.detail.contains("stack overflow"),
        )
        // The engine-level stack failure never crashes the instance lifecycle: a later
        // execution (fresh instance) succeeds.
        val next = support.client.execute(support.params(support.newExecutionId("recursion-next"), "return 6 * 7"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("42", next.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun oomAtDefaultHeapIsStable() {
        // HXA-050-pinned: 64 MiB exhaustion is a JS-level OOM. The catch variable is
        // either an Error whose message carries "out of memory" (API 36; the wrapper
        // prefixes it and the substring survives) or, on API 29, literal null when the
        // bulk allocation fails to allocate the Error object itself — the wrapper
        // rethrows a caught null verbatim so the host-side empty-message OOM form
        // survives (see JsAbiAssembly). Either path classifies OOM here.
        val result =
            support.client.execute(
                support.params(
                    support.newExecutionId("oom64"),
                    "var a = new Array(32 * 1024 * 1024).fill(1); a.length",
                ),
            )
        assertEquals("expected OOM, got ${result.status}: ${result.detail}", JsExecutionStatus.OOM, result.status)
        assertIsolated(result)
        val next = support.client.execute(support.params(support.newExecutionId("oom64-next"), "return 1 + 1"))
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
        // The wrapper's conservative code-unit check fires first (1 MiB+1 units > 256 KiB)
        // and carries the stable marker.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("outputlimit"), """return "a".repeat(1024 * 1024 + 1)"""),
            )
        assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
        assertTrue(result.outputUtf8.isEmpty())
        assertTrue(
            "detail must carry the wrapper marker, got: ${result.detail}",
            result.detail.contains(JsAbiAssembly.OUTPUT_LIMIT_MARKER),
        )
    }

    @Test
    fun outputAboveInlineCapWithoutOutputPfdIsRejected() {
        // 100 KiB output: within the 256 KiB limit but above the 64 KiB inline parcel cap,
        // and no output PFD was provided → stable OUTPUT_LIMIT (service-side check).
        val result =
            support.client.execute(
                support.params(support.newExecutionId("inlinecap"), """return "a".repeat(100 * 1024)"""),
            )
        assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
    }

    @Test
    fun sourceOverLimitIsRejectedBeforeExecution() {
        val source = "return 1 + 1 // " + "pad".repeat(110_000) // ~330 KiB > 256 KiB default
        val result = support.client.execute(support.params(support.newExecutionId("srclimit"), source))
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
        assertEquals(-1, result.servicePid) // rejected pre-bind: no instance spawned
    }

    @Test
    fun inputOverLimitIsRejectedBeforeExecution() {
        val input = ByteArray(3 * 1024 * 1024) { ('a'.code + it % 26).toByte() }
        val result =
            support.client.execute(
                support.params(support.newExecutionId("inputlimit"), "return 1 + 1", inputJsonUtf8 = input),
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
                    "return 1 + 1",
                    limits = JsExecutionLimits(timeoutMs = 0),
                ),
            )
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
        assertEquals(-1, result.servicePid)
    }

    @Test
    fun blankExecutionIdIsRejectedBeforeExecution() {
        val result = support.client.execute(support.params("", "return 1 + 1"))
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
    }

    @Test
    fun dynamicCompilationRemainsBlockedByEngine() {
        // ADR-0015 regression anchor through the production path: all dynamic-compilation
        // call paths throw the engine's `eval is not supported`. The wrapper prefixes the
        // error but the engine block stays intact (the attack suite extends this).
        val sources =
            listOf(
                """eval("1+1")""",
                """new Function("return 1")()""",
                """Object.constructor.constructor("return 1")()""",
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
