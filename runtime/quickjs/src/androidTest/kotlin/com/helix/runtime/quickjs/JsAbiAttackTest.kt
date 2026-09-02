package com.helix.runtime.quickjs

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * HXA-052 wrapper-escape and ABI attack suite (roadmap M5 / doc 03 §3.2): input global
 * leakage, host-literal escape, eval/Function-constructor dynamic compilation, wrapper
 * control-plane tampering (stringify/JSON override), error-prefix integrity, output
 * boundary arithmetic (UTF-16 code-unit check vs authoritative UTF-8 byte check), bridge
 * absence, and input round trips. Every execution uses a fresh unique isolated instance.
 */
class JsAbiAttackTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(240)

    private val support = JsExecutionTestSupport

    @Test
    fun inputGlobalsDoNotLeak() {
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abiglobals"),
                    source = SRC_GLOBALS,
                    inputJsonUtf8 = INPUT_V1.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_GLOBALS, result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertNotEquals("service must run in a different process", Process.myPid(), result.servicePid)
        assertNotEquals("service must run in a different (isolated) UID", Process.myUid(), result.serviceUid)
    }

    @Test
    fun inputAssignmentIsRejectedAndCreatesNoGlobal() {
        // Strict mode: input is a local const — assigning it is a runtime TypeError.
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abiasign"),
                    source = SRC_ASSIGN,
                    inputJsonUtf8 = INPUT_V1.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue("detail must be non-blank, got: '${result.detail}'", result.detail.isNotBlank())
        // No global was created: a fresh instance sees no `input` on globalThis.
        val next =
            support.client.execute(
                support.params(support.newExecutionId("abiasign2"), SRC_ASSIGN2),
            )
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals(EXP_ASSIGN2, next.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun evalBlocked() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abieval"), SRC_EVAL),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue(
            "detail must carry the engine eval block, got: ${result.detail}",
            result.detail.contains("eval is not supported") && result.detail.contains(JsAbiAssembly.ERROR_PREFIX),
        )
    }

    @Test
    fun functionConstructorVariantsBlocked() {
        val sources = listOf(SRC_FN1, SRC_FN2, SRC_FN3)
        sources.forEachIndexed { index, source ->
            val result =
                support.client.execute(support.params(support.newExecutionId("abifn-$index"), source))
            assertEquals("expected JS_ERROR for: $source", JsExecutionStatus.JS_ERROR, result.status)
            assertTrue(
                "detail must carry the engine eval block, got: ${result.detail}",
                result.detail.contains("eval is not supported"),
            )
        }
    }

    @Test
    fun wrapperEscapeViaInputPayload() {
        // The input JSON value carries the classic IIFE-escape payload. The wrapper splices
        // the input as a HOST-ENCODED string literal argument, so the payload is inert data.
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abiesc"),
                    source = SRC_ESCAPE,
                    inputJsonUtf8 = INPUT_PAYLOAD_OBJ.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_ESCAPE, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun bareStringInputWithEscapePayload() {
        // The entire input document is a JSON string carrying the escape payload.
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abibare"),
                    source = SRC_BARE,
                    inputJsonUtf8 = INPUT_PAYLOAD_BARE.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_BARE, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun degenerateBraceEscapeFailsClosedAtParse() {
        // Brace-escape probe: a user `}` closes helixMain early, the user `return 5`
        // lands in the IIFE body, and the template's own closing brace is left dangling.
        // The assembly is then structurally broken → the engine rejects it as a syntax
        // error BEFORE anything executes (fail closed): the side-effect line in the user
        // source never runs and nothing leaves the isolated instance.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abideg"), SRC_DEG),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue("detail must be non-blank, got: '${result.detail}'", result.detail.isNotBlank())
    }

    @Test
    fun stringifyOverrideIneffective() {
        // The wrapper captures JSON.stringify BEFORE helixMain runs — user code cannot
        // replace it to bypass the result contract.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abistr"), SRC_STR_OVERRIDE),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_OK, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun globalJsonReplacementIneffective() {
        // Replacing the whole global JSON object is equally ineffective: the wrapper's
        // captured stringify reference is the original engine function.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abirepl"), SRC_JSON_REPLACE),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_OK, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun emptyMessageThrowIsNonBlankPrefixed() {
        // User Error with an EMPTY message: the wrapper falls back to String(e) ("Error")
        // — the rethrow is always a non-blank prefixed string (never the pinned empty
        // engine OOM form).
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abiempty"), SRC_EMPTY_THROW),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, result.status)
        assertTrue(
            "detail must carry the wrapper prefix, got: '${result.detail}'",
            result.detail.startsWith(JsAbiAssembly.ERROR_PREFIX),
        )
    }

    @Test
    fun nonErrorThrowsCarryPrefixedStringForm() {
        val fortyTwo =
            support.client.execute(
                support.params(support.newExecutionId("abit42"), SRC_THROW_42),
            )
        assertEquals(JsExecutionStatus.JS_ERROR, fortyTwo.status)
        assertTrue(
            "detail must carry prefix + the thrown value, got: ${fortyTwo.detail}",
            fortyTwo.detail.contains(JsAbiAssembly.ERROR_PREFIX) && fortyTwo.detail.contains("42"),
        )
        // `throw null` (and the engine's API 29 bulk-OOM surface form) is a caught
        // null: the wrapper rethrows it VERBATIM so the host-side empty-message OOM
        // form survives — a user `throw null` is indistinguishable from the OOM form
        // and takes the same OOM path (documented, accepted conflation).
        val nullThrow =
            support.client.execute(
                support.params(support.newExecutionId("abinull"), SRC_THROW_NULL),
            )
        assertEquals(
            "caught null is the engine OOM surface form, got ${nullThrow.status}: ${nullThrow.detail}",
            JsExecutionStatus.OOM,
            nullThrow.status,
        )
    }

    @Test
    fun outputJustOverLimitIsRejectedWithMarker() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abiover"), SRC_OVER_LIMIT),
            )
        assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
        assertTrue(result.outputUtf8.isEmpty())
        assertTrue(
            "detail must carry the wrapper marker, got: ${result.detail}",
            result.detail.contains(JsAbiAssembly.OUTPUT_LIMIT_MARKER),
        )
    }

    @Test
    fun exactByteBoundarySucceeds() {
        // ASCII string of n units → JSON document 2n+2 UTF-8 bytes. n = 256 KiB - 2 gives
        // exactly maxOutputBytes (262144) → the boundary is inclusive.
        val outputFile = File(support.context.cacheDir, "js-abi-boundary-${System.nanoTime()}.out")
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abibound"),
                    source = SRC_BOUNDARY,
                    outputFile = outputFile,
                ),
            )
        try {
            assertEquals(JsExecutionStatus.SUCCESS, result.status)
            assertEquals(256L * 1024, result.outputBytes)
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun byteCheckOverLimitIsRejected() {
        // U+4E2D is 1 UTF-16 code unit but 3 UTF-8 bytes: n = 87381 → the document is
        // 87383 code units (the wrapper's conservative check PASSES, 87383 <= 262144)
        // but 262145 UTF-8 bytes → the service's authoritative byte check rejects with
        // the stable OUTPUT_LIMIT detail. (For ASCII the two checks coincide and the
        // wrapper fires first — pinned by outputJustOverLimitIsRejectedWithMarker.)
        val outputFile = File(support.context.cacheDir, "js-abi-byteover-${System.nanoTime()}.out")
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abibyte"),
                    source = SRC_BYTE_OVER,
                    outputFile = outputFile,
                ),
            )
        try {
            assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
            assertTrue(
                "detail must carry the byte-check reason, got: ${result.detail}",
                result.detail.contains("exceeds maxOutputBytes"),
            )
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun emojiUtf8BoundaryIsEnforcedByByteCheck() {
        // 🚀 is 2 UTF-16 code units and 4 UTF-8 bytes → document 4n+2 bytes.
        // n = 65535 → 262142 bytes (<= 262144) SUCCESS; n = 65536 → 262146 bytes
        // (the wrapper unit check PASSES at 131072 units, only the service byte check
        // catches it) OUTPUT_LIMIT.
        val okFile = File(support.context.cacheDir, "js-abi-emoji-ok-${System.nanoTime()}.out")
        val ok =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abiemoki"),
                    source = SRC_EMOJI_1,
                    outputFile = okFile,
                ),
            )
        try {
            assertEquals(JsExecutionStatus.SUCCESS, ok.status)
            assertEquals(262142L, ok.outputBytes)
        } finally {
            okFile.delete()
        }
        val overFile = File(support.context.cacheDir, "js-abi-emoji-over-${System.nanoTime()}.out")
        val over =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abieko"),
                    source = SRC_EMOJI_2,
                    outputFile = overFile,
                ),
            )
        try {
            assertEquals(JsExecutionStatus.OUTPUT_LIMIT, over.status)
            assertTrue(
                "detail must carry the byte-check reason, got: ${over.detail}",
                over.detail.contains("exceeds maxOutputBytes"),
            )
        } finally {
            overFile.delete()
        }
    }

    @Test
    fun noHostBridgeGlobalsExist() {
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abibr"), SRC_BRIDGE),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_BRIDGE, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun jsonRoundTripWithControlAndUnicode() {
        // Control characters (NUL, LF) arrive as strict JSON escapes and come back intact;
        // non-ASCII round trips byte-exact (the wrapper literal is host-encoded UTF-8).
        val bs = 92.toChar()
        val inputJson = INPUT_RT_A + bs + INPUT_RT_B + bs + INPUT_RT_C
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abirt"),
                    source = SRC_ROUNDTRIP,
                    inputJsonUtf8 = inputJson.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_ROUNDTRIP, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun deepNestingRoundTrip() {
        // 300-deep JSON input (under the 512 contract cap) round trips through the
        // wrapper literal and a recursive user walk. Quotes are built from their code
        // point so the document is unambiguously `{"c":{"c":...}}` (object nesting —
        // raw-string quote runs would corrupt this into string values).
        val q = 34.toChar()
        var doc = "{" + q + "v" + q + ":42}"
        repeat(300) { doc = "{" + q + "c" + q + ":" + doc + "}" }
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("abideep"),
                    source = SRC_DEEP,
                    inputJsonUtf8 = doc.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_DEEP, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun noInputMeansNullAndInvalidInputIsRejected() {
        // Absent input → the wrapper's null literal → JSON.parse(null) → null.
        val result =
            support.client.execute(
                support.params(support.newExecutionId("abinoin"), SRC_NULL_INPUT),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(EXP_NULL_INPUT, result.outputUtf8.toString(StandardCharsets.UTF_8))
        // Non-JSON input bytes are rejected pre-bind (doc 03 §4.2 input contract).
        val bad =
            support.client.execute(
                support.params(
                    support.newExecutionId("abibadin"),
                    source = SRC_NULL_INPUT,
                    inputJsonUtf8 = "{not json".toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, bad.status)
        assertEquals(-1, bad.servicePid)
    }

    companion object {
        private const val SRC_GLOBALS =
            """return { inputJsonGlobal: typeof globalThis.__helixInputJson, """ +
                """inputGlobal: typeof globalThis.input, """ +
                """localInput: typeof input, localJson: typeof __helixInputJson }"""
        private const val SRC_ASSIGN = """input = { x: 1 }; return "unreachable""""
        private const val SRC_ASSIGN2 = """return typeof globalThis.input"""
        private const val SRC_EVAL = """return eval("1+1")"""
        private const val SRC_FN1 = """return new Function("return 1")()"""
        private const val SRC_FN2 = """return Object.constructor.constructor("return 1")()"""
        private const val SRC_FN3 = """return JSON.parse.constructor("return 1")()"""
        private const val SRC_ESCAPE = """return { pwned: typeof globalThis.__pwned, round: input.p }"""
        private const val SRC_BARE = """return { isStr: typeof input === "string", pwned: typeof globalThis.__pwned }"""
        private const val SRC_DEG = """globalThis.__side = 1;
}
return 5"""
        private const val SRC_STR_OVERRIDE = """JSON.stringify = () => "999"; return { ok: true }"""
        private const val SRC_JSON_REPLACE = """JSON = { stringify: () => "999" }; return { ok: true }"""
        private const val SRC_EMPTY_THROW = """throw new Error("")"""
        private const val SRC_THROW_42 = """throw 42"""
        private const val SRC_THROW_NULL = """throw null"""
        private const val SRC_OVER_LIMIT = """return "a".repeat(256 * 1024 + 1)"""
        private const val SRC_BOUNDARY = """return "a".repeat(256 * 1024 - 2)"""

        // U+4E2D (中) via fromCharCode: 1 code unit / 3 UTF-8 bytes, keeps this source
        // ASCII-only (raw-string/tooling-safe) while exercising the multibyte byte check.
        private const val SRC_BYTE_OVER = """return String.fromCharCode(0x4e2d).repeat(87381)"""
        private const val SRC_EMOJI_1 = """return "🚀".repeat(65535)"""
        private const val SRC_EMOJI_2 = """return "🚀".repeat(65536)"""
        private const val SRC_BRIDGE =
            """return { fetch: typeof fetch, require: typeof require, java: typeof java, """ +
                """android: typeof android, XMLHttpRequest: typeof XMLHttpRequest, """ +
                """WebSocket: typeof WebSocket, process: typeof process }"""
        private const val SRC_ROUNDTRIP =
            """return { len: input.s.length, u: input.u, first: input.arr[0], """ +
                """zero: input.s.indexOf(String.fromCharCode(0)) }"""
        private const val SRC_DEEP =
            """function d(o) { return o.c === undefined ? 0 : 1 + d(o.c); } """ +
                """function w(o) { return o.v !== undefined ? o.v : w(o.c); } """ +
                """return { depth: d(input), v: w(input) }"""
        private const val SRC_NULL_INPUT = """return { i: input === null }"""
        private const val INPUT_V1 = """{"v":1}"""
        private const val INPUT_PAYLOAD_OBJ = """{"p":"})(); globalThis.__pwned = 1; */ //"}"""
        private const val INPUT_PAYLOAD_BARE = """"})(); globalThis.__pwned = 1; */ //""""
        private const val INPUT_RT_A = """{"s":"a"""
        private const val INPUT_RT_B = """u0000b"""
        private const val INPUT_RT_C = """n🚀","arr":[1,2.5,-3,true,null,"x"],"u":"héllo 日本語"}"""
        private const val EXP_GLOBALS =
            """{"inputJsonGlobal":"undefined","inputGlobal":"undefined","localInput":"object","""" +
                """localJson":"string"}"""
        private const val EXP_ASSIGN2 = """"undefined""""
        private const val EXP_ESCAPE = """{"pwned":"undefined","round":"})(); globalThis.__pwned = 1; */ //"}"""
        private const val EXP_BARE = """{"isStr":true,"pwned":"undefined"}"""
        private const val EXP_OK = """{"ok":true}"""
        private const val EXP_BRIDGE =
            """{"fetch":"undefined","require":"undefined","java":"undefined","android":"undefined","""" +
                """XMLHttpRequest":"undefined","WebSocket":"undefined","process":"undefined"}"""
        private const val EXP_ROUNDTRIP = """{"len":6,"u":"héllo 日本語","first":1,"zero":1}"""
        private const val EXP_DEEP = """{"depth":300,"v":42}"""
        private const val EXP_NULL_INPUT = """{"i":true}"""
    }
}
