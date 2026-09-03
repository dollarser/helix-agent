package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * HXA-052 wrapper-assembly contract (doc 03 §3.2 template + HXA-052 hardening):
 * template shape, the tamper-proof error path, the conservative output threshold,
 * the injection point and the bounded-after-assembly size.
 */
class JsAbiAssemblyTest {
    @Test
    fun templateShapeMatchesTheDoc32Wrapper() {
        val program = JsAbiAssembly.build("return 1;", "null", 262144)
        assertTrue(program.startsWith("((__helixInputJson) => {\n"))
        assertTrue("strict mode first statement", program.contains("\"use strict\";\n"))
        assertTrue(program.contains("const input = JSON.parse(__helixInputJson);"))
        // `input` stays the IIFE local const: helixMain is parameterless and captures
        // it by closure, so a user `input = ...` is the strict-mode read-only TypeError
        // (a parameter would silently shadow the const and make the assignment legal).
        assertTrue(
            "user source is the helixMain body; input is NOT a parameter",
            program.contains("function helixMain() {\nreturn 1;\n  }"),
        )
        assertTrue("IIFE invocation with the input literal", program.endsWith("})(null);\n"))
        // Exactly one invocation splice point — the literal can appear nowhere else.
        assertEquals(program.lastIndexOf("})("), program.indexOf("})("))
    }

    @Test
    fun stringifyIsCapturedBeforeHelixMainAndUsedForEncoding() {
        val program = JsAbiAssembly.build("return 1;", "null", 100)
        val capture = program.indexOf("const stringify = JSON.stringify;")
        val mainDef = program.indexOf("function helixMain")
        assertTrue("capture must precede the user-code definition", capture in 0 until mainDef)
        assertTrue("result encoding uses the captured binding", program.contains("const out = stringify(value);"))
        assertTrue("no direct JSON.stringify call on the result", !program.contains("JSON.stringify(helixMain"))
    }

    @Test
    fun errorPathRethrowsPrefixedStringAndPreservesBlankFormVerbatim() {
        val program = JsAbiAssembly.build("return 1;", "null", 100)
        assertTrue(
            "prefixed rethrow as a STRING (Error-override immune)",
            program.contains("throw \"helixMain threw: \" + m;"),
        )
        assertTrue(
            "blank descriptor rethrows the original value (OOM form survives)",
            program.contains("if (m === \"\") throw e;"),
        )
        // A caught null (the engine's API 29 bulk-OOM surface form) is rethrown
        // VERBATIM before any descriptor extraction, so the host-side empty-message
        // OOM form survives (a user `throw null` takes the same path — accepted).
        assertTrue(
            "caught null rethrown verbatim (API 29 OOM surface form survives)",
            program.contains("if (e === null) throw e;"),
        )
        val catchOpen = program.indexOf("} catch (e) {")
        val nullRethrow = program.indexOf("if (e === null) throw e;")
        val extract = program.indexOf("m = (e !== null")
        assertTrue(
            "null rethrow is the FIRST catch action",
            catchOpen in 0 until nullRethrow && nullRethrow in 0 until extract,
        )
        assertTrue(program.contains(JsAbiAssembly.ERROR_PREFIX.dropLast(0)))
    }

    @Test
    fun outputLimitThrowRunsAfterTheUserErrorCatch() {
        val program = JsAbiAssembly.build("return 1;", "null", 100)
        val userCall = program.indexOf("value = helixMain();")
        val encode = program.indexOf("const out = stringify(value);")
        val limitThrow = program.indexOf("throw \"helix output limit exceeded: \" + out.length;")
        assertTrue("encode after the user call", userCall in 0 until encode)
        assertTrue("limit throw after the encoding (outside the user-error catch)", limitThrow > encode)
        assertTrue(program.contains("if (out.length > 100)"))
    }

    @Test
    fun undefinedResultMapsToJsonNull() {
        val program = JsAbiAssembly.build("return 1 // no return statement", "null", 100)
        assertTrue(program.contains("if (out === undefined) return \"null\";"))
        assertTrue(program.contains("return out;\n})("))
    }

    @Test
    fun nullInputInjectsTheUnquotedNullLiteral() {
        val program = JsAbiAssembly.build("return 1;", JsAbiAssembly.inputLiteral(null), 100)
        assertTrue(program.endsWith("})(null);\n"))
        assertEquals("null", JsAbiAssembly.inputLiteral(ByteArray(0)))
    }

    @Test
    fun inputLiteralIsSplicedVerbatimAtTheInvocation() {
        val literal = JsInputLiteral.encode("""{"k":"v"}""")
        val program = JsAbiAssembly.build("return 1;", literal, 100)
        assertTrue(program.endsWith("})($literal);\n"))
    }

    @Test
    fun outputThresholdIsTheNumericMaxOutputBytes() {
        assertTrue(
            JsAbiAssembly
                .build("return 1;", "null", JsExecutionLimits.DEFAULT_MAX_OUTPUT_BYTES)
                .contains("if (out.length > 262144)"),
        )
        assertTrue(JsAbiAssembly.build("return 1;", "null", 1234).contains("if (out.length > 1234)"))
    }

    @Test
    fun userSourceIsNewlineBoundedOnBothSides() {
        // A trailing line comment without a newline must not swallow the closing brace.
        val program = JsAbiAssembly.build("return 1 // trailing", "null", 100)
        assertTrue(program.contains("return 1 // trailing\n  }"))
        // Empty source is legal (helixMain returns undefined -> wrapper emits "null").
        assertTrue(JsAbiAssembly.build("", "null", 100).contains("function helixMain() {\n\n  }"))
    }

    @Test
    fun wrappedSizeBoundCoversTheWorstCaseAssembly() {
        // The bound accounts for worst-case 6x escape expansion of the input.
        assertEquals(
            256 * 1024 + 8 * 1024 + 6 * 2 * 1024 * 1024,
            JsAbiAssembly.maxWrappedBytes(256 * 1024, 2 * 1024 * 1024),
        )
        // Adversarial assembly: source near the limit + maximally-escaping input.
        val source = "a".repeat(256 * 1024 - 16)
        val inputLiteral = JsInputLiteral.encode("\u0000".repeat(4096))
        val program = JsAbiAssembly.build(source, inputLiteral, 262144)
        assertTrue(
            "assembled program ${program.length} must stay under the bound ${JsAbiAssembly.maxWrappedBytes(
                256 * 1024,
                4096,
            )}",
            program.toByteArray(StandardCharsets.UTF_8).size <= JsAbiAssembly.maxWrappedBytes(256 * 1024, 4096),
        )
    }

    @Test
    fun markersAreStable() {
        assertEquals("helixMain threw: ", JsAbiAssembly.ERROR_PREFIX)
        assertEquals("helix output limit exceeded: ", JsAbiAssembly.OUTPUT_LIMIT_MARKER)
        // HXA-054 device-pinned: the Zipline 1.27.0 / QuickJS 2021-03-27 engine says the
        // bare `circular reference` for a cyclic stringify (the V8 phrasing does not
        // exist in the QuickJS binary — see CIRCULAR_RESULT_MARKER KDoc).
        assertEquals("circular reference", JsAbiAssembly.CIRCULAR_RESULT_MARKER)
    }
}
