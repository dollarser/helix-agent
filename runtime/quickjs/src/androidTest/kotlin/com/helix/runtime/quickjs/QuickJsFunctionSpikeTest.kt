package com.helix.runtime.quickjs

import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HXA-050 spike capability 4 (roadmap M5 + doc 03 §3.3): `eval`/`Function`/constructor
 * dynamic-compilation surface.
 *
 * Probe findings on API 29 + API 36 arm64-v8a (the real, pinned behavior):
 * Zipline 1.27.0 does NOT delete the `eval`/`Function` globals — `typeof eval` and
 * `typeof Function` both report `"function"` — but it replaces them with STUBS so that
 * every dynamic-compilation CALL path throws `QuickJsException: "eval is not supported"`.
 *
 * So the security property is NOT "`typeof Function === 'undefined'`"; it is "invoking
 * any dynamic-compilation entry point throws". This test pins that property. If a future
 * Zipline version re-enables any path (returns a value instead of throwing), the test
 * fails and HXA-052's wrapper must re-derive its sealing strategy.
 */
class QuickJsFunctionSpikeTest {
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
    fun typeofReportsFunctionStubsPresent() {
        // Pinned observation: the globals exist as stubs (typeof === "function"),
        // they are not deleted. HXA-052 must not assume `typeof === "undefined"`.
        assertEquals("function", quickJs.evaluate("typeof eval"))
        assertEquals("function", quickJs.evaluate("typeof Function"))
        assertEquals("function", quickJs.evaluate("typeof Function.prototype.call"))
    }

    @Test
    fun directEvalCallThrows() {
        assertDynamicCompilationBlocked("eval('1+1')")
    }

    @Test
    fun directFunctionConstructorThrows() {
        assertDynamicCompilationBlocked("new Function('return 1')()")
    }

    @Test
    fun functionAsPlainCallThrows() {
        assertDynamicCompilationBlocked("Function('return 1')()")
    }

    @Test
    fun objectConstructorChainThrows() {
        // The classic `Function` bypass via the prototype chain.
        assertDynamicCompilationBlocked("Object.constructor.constructor('return 42')()")
    }

    @Test
    fun stringConstructorChainThrows() {
        assertDynamicCompilationBlocked("String.constructor.constructor('return 7')()")
    }

    @Test
    fun arrayConstructorChainThrows() {
        assertDynamicCompilationBlocked("[].constructor.constructor('return 3')()")
    }

    private fun assertDynamicCompilationBlocked(source: String) {
        val error =
            runCatching { quickJs.evaluate(source) }.exceptionOrNull()
                ?: throw AssertionError("expected dynamic-compilation block, but $source returned a value")
        assertTrue(
            "expected QuickJsException for $source, got ${error.javaClass.name}",
            error is QuickJsException,
        )
        assertTrue(
            "expected 'eval is not supported' for $source, got: ${error.message?.take(160)}",
            error.message?.contains("eval is not supported") == true,
        )
    }
}
