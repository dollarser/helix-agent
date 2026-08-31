package com.helix.core.model.internal

import com.helix.core.model.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalJsonTest {
    private val backslash = "\\"

    @Test
    fun escapeCoversAllMandatorySequences() {
        val input =
            buildString {
                append('a')
                append('"')
                append('b')
                append('\\')
                append('c')
                append('\n')
                append('d')
                append('\t')
                append('e')
                append(0x01.toChar())
                append('f')
            }
        val expected =
            buildString {
                append('"')
                append('a')
                append(backslash)
                append('"')
                append('b')
                append(backslash)
                append(backslash)
                append('c')
                append(backslash)
                append('n')
                append('d')
                append(backslash)
                append('t')
                append('e')
                append(backslash)
                append("u0001")
                append('f')
                append('"')
            }
        assertEquals(expected, Json.string(input))
        // Round trip through the strict parser.
        val parsed = parseJson(Json.string(input)) as JsonNode.Str
        assertEquals(input, parsed.value)
    }

    @Test
    fun escapeKeepsUnicodeTextVerbatim() {
        val text = "héllo ✓ 世界"
        assertEquals(text, (parseJson(Json.string(text)) as JsonNode.Str).value)
    }

    @Test
    fun numbersParseTo64BitIntegersOnly() {
        assertEquals(JsonNode.Num(0), parseJson("0"))
        assertEquals(JsonNode.Num(-5), parseJson("-5"))
        assertEquals(JsonNode.Num(9_223_372_036_854_775_807L), parseJson("9223372036854775807"))
        assertThrows<IllegalArgumentException> { parseJson("1.5") }
        assertThrows<IllegalArgumentException> { parseJson("01") }
        assertThrows<IllegalArgumentException> { parseJson("1e3") }
        assertThrows<IllegalArgumentException> { parseJson("-0.0") }
        assertThrows<IllegalArgumentException> { parseJson("9223372036854775808") }
        assertThrows<IllegalArgumentException> { parseJson("99999999999999999999999999") }
    }

    @Test
    fun objectsKeepFieldOrderAndRejectDuplicates() {
        val parsed = parseJson("{ \"a\":1, \"b\":[true], \"c\":{} }") as JsonNode.Obj
        assertEquals(listOf("a", "b", "c"), parsed.entries.map { it.first })
        assertThrows<IllegalArgumentException> { parseJson("{\"a\":1,\"a\":2}") }
    }

    @Test
    fun nullIsAcceptedButTrailingContentAndTruncationAreRejected() {
        assertEquals(JsonNode.Null, parseJson("null"))
        assertThrows<IllegalArgumentException> { parseJson("{\"a\":1} trailing") }
        assertThrows<IllegalArgumentException> { parseJson("") }
        assertThrows<IllegalArgumentException> { parseJson("[1,2") }
    }

    @Test
    fun unescapedControlCharactersAreRejected() {
        assertThrows<IllegalArgumentException> { parseJson("\"a" + 0x01.toChar() + "b\"") }
        assertThrows<IllegalArgumentException> { parseJson("\"a" + '\n' + "b\"") }
    }

    @Test
    fun unicodeEscapesDecode() {
        val bs = backslash
        val parsed = parseJson("\"" + bs + "u0041" + bs + "u00e9\"") as JsonNode.Str
        assertEquals("Aé", parsed.value)
        assertThrows<IllegalArgumentException> { parseJson("\"" + bs + "u00") }
        assertThrows<IllegalArgumentException> { parseJson("\"" + bs + "uGGGG\"") }
    }

    @Test
    fun objectBodyIsFixedOrderAndCompact() {
        val body =
            Json.objectBody(
                listOf(
                    "b" to Json.long(2),
                    "a" to Json.string("x"),
                    "c" to Json.bool(true),
                ),
            )
        assertEquals("{\"b\":2,\"a\":\"x\",\"c\":true}", body)
    }

    @Test
    fun objectFromSortedEntriesSortsByKey() {
        val body =
            Json.objectFromSortedEntries(
                listOf(
                    "z" to Json.string("1"),
                    "a" to Json.string("2"),
                    "m" to Json.string("3"),
                ),
            )
        assertTrue(body.startsWith("{\"a\":\"2\",\"m\":\"3\",\"z\":\"1\""))
    }
}
