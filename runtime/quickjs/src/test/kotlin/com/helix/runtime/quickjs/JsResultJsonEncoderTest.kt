package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * HXA-051 result-encoder contract (doc 03 §3.2/§4.6): JSON primitives/objects/arrays only,
 * deterministic escaping, non-encodable and circular values fail (never success).
 */
class JsResultJsonEncoderTest {
    @Test
    fun primitivesEncodeCanonically() {
        assertEquals("null", JsResultJson.encode(null).toUtf8())
        assertEquals("true", JsResultJson.encode(true).toUtf8())
        assertEquals("false", JsResultJson.encode(false).toUtf8())
        assertEquals("42", JsResultJson.encode(42).toUtf8())
        assertEquals("-7", JsResultJson.encode(-7).toUtf8())
        assertEquals("42", JsResultJson.encode(42L).toUtf8())
        assertEquals("1.5", JsResultJson.encode(1.5).toUtf8())
        // Integral doubles render without a fractional part (JSON.stringify semantics).
        assertEquals("7", JsResultJson.encode(7.0).toUtf8())
    }

    @Test
    fun nonFiniteNumbersAreRejected() {
        assertFailure(Double.NaN)
        assertFailure(Double.POSITIVE_INFINITY)
        assertFailure(Double.NEGATIVE_INFINITY)
    }

    @Test
    fun nestedStringsEscapeControlCharactersQuotesAndBackslashes() {
        val text = "a\"b\\c\nd\re\tf\bg\u0001h"
        assertEquals(
            "{\"k\":\"a\\\"b\\\\c\\nd\\re\\tf\\bg\\u0001h\"}",
            JsResultJson.encode(mapOf("k" to text)).toUtf8(),
        )
        assertEquals(
            "{\"line\\fbreak\":2}",
            JsResultJson.encode(mapOf("line\u000Cbreak" to 2)).toUtf8(),
        )
    }

    @Test
    fun unicodePassesThroughAsUtf8() {
        val text = "héllo 🚀 日本語"
        assertEquals(text, JsResultJson.encode(text).toUtf8())
        assertEquals(text.toByteArray(StandardCharsets.UTF_8).size, JsResultJson.encode(text).size)
    }

    @Test
    fun jsonTextStringResultIsNotDoubleEncoded() {
        // HXA-052 contract: the wrapper returns JSON.stringify(...) as a JS string; the
        // protocol output must be that JSON text verbatim, not a quoted re-encoding.
        val json = "{\"a\":1}"
        assertEquals(json, JsResultJson.encode(json).toUtf8())
    }

    @Test
    fun mapsAndListsEncodeRecursively() {
        assertEquals(
            """{"a":1,"b":"x","c":[true,null,1.5],"d":{}}""",
            JsResultJson
                .encode(
                    mapOf(
                        "a" to 1,
                        "b" to "x",
                        "c" to listOf(true, null, 1.5),
                        "d" to emptyMap<String, Int>(),
                    ),
                ).toUtf8(),
        )
        assertEquals("[]", JsResultJson.encode(emptyList<String>()).toUtf8())
    }

    @Test
    fun circularReferencesAreRejected() {
        val list = mutableListOf<Any?>()
        list.add(list)
        assertFailure(list)
        val map = HashMap<String, Any?>()
        map["self"] = map
        assertFailure(map)
        val outer = listOf(listOf(map))
        // A DAG (shared but acyclic) is fine: the same container encoded twice.
        val shared = listOf(1, 2)
        assertEquals("[[1,2],[1,2]]", JsResultJson.encode(listOf(shared, shared)).toUtf8())
        // Outer wrapper over the cyclic structure must also fail.
        assertFailure(outer)
    }

    @Test
    fun unsupportedTypesAndKeysAreRejected() {
        assertFailure(RuntimeException("not json"))
        assertFailure(mapOf(1 to "int key"))
        assertFailure(mapOf("k" to byteArrayOf(1)))
    }

    @Test
    fun stringResultPassesThroughByteForByte() {
        // HXA-052 production path: the wrapper returns a JSON string literal, so the
        // encoder must not re-encode or alter it.
        val json = """{"unicode":"🚀","nested":{"a":[1,2,3]}}"""
        assertEquals(json, JsResultJson.encode(json).toUtf8())
    }

    private fun assertFailure(value: Any?) {
        try {
            JsResultJson.encode(value)
            fail("expected EncodingFailure for ${describe(value)}")
        } catch (e: JsResultJson.EncodingFailure) {
            assertTrue("failure must carry a stable reason", !e.message.isNullOrBlank())
        }
    }

    private fun describe(value: Any?): String =
        when (value) {
            null -> "null"
            is Number -> value.javaClass.simpleName
            else -> value.javaClass.simpleName
        }

    private fun ByteArray.toUtf8(): String = String(this, StandardCharsets.UTF_8)
}
