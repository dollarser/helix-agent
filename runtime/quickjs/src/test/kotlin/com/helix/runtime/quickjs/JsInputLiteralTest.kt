package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * HXA-052 input-literal encoding (doc 03 §3.2): the host encodes the validated JSON
 * document as a JavaScript string literal. The escape set is a security property —
 * the encoded literal must be impossible to close from inside.
 */
class JsInputLiteralTest {
    @Test
    fun quotesAndBackslashesAreEscaped() {
        // {"a":"b"}  ->  "{\"a\":\"b\"}"
        assertEquals("\"{\\\"a\\\":\\\"b\\\"}\"", JsInputLiteral.encode("""{"a":"b"}"""))
        // a"b\c  ->  "a\"b\\c"
        assertEquals("\"a\\\"b\\\\c\"", JsInputLiteral.encode("a\"b\\c"))
    }

    @Test
    fun controlCharactersUseEscapeForms() {
        // NUL and U+0001 -> \u0000 \u0001 ; \n \r \t \b \f -> two-char forms ; \u001f -> \u001f
        val input = "\u0000\u0001\n\r\t\b\u000C\u001f"
        val expected = "\"\\u0000\\u0001\\n\\r\\t\\b\\f\\u001f\""
        assertEquals(expected, JsInputLiteral.encode(input))
    }

    @Test
    fun lineAndParagraphSeparatorsAreEscaped() {
        assertEquals("\"a\\u2028b\\u2029c\"", JsInputLiteral.encode("a\u2028b\u2029c"))
    }

    @Test
    fun unicodeAndSurrogatePairsPassThroughRaw() {
        val text = "héllo 🚀 日本語"
        assertEquals("\"$text\"", JsInputLiteral.encode(text))
        assertEquals(
            text.toByteArray(StandardCharsets.UTF_8).size + 2,
            JsInputLiteral.encode(text).toByteArray(StandardCharsets.UTF_8).size,
        )
    }

    @Test
    fun wrapEscapePayloadIsFullyNeutralized() {
        // The canonical wrap-escape payload: a quote + backslash to try to close the
        // literal, then IIFE-closing code that sets a global, a comment-closing
        // sequence and a line comment.
        val payload = "\"\\); globalThis.__pwned = 1; */ //"
        val literal = JsInputLiteral.encode(payload)
        assertEquals("\"\\\"\\\\); globalThis.__pwned = 1; */ //\"", literal)
        assertSingleQuotedLiteral(literal)
    }

    @Test
    fun splicedInvocationStaysWellFormed() {
        val payload = "})(); globalThis.__pwned = 1; //"
        val literal = JsInputLiteral.encode(payload)
        val invocation = "})($literal);"
        // After splicing, the ONLY unescaped quotes are the literal's own two.
        assertEquals(2, invocation.count { c -> c == '"' })
        assertSingleQuotedLiteral(literal)
    }

    @Test
    fun emptyDocumentEncodesToEmptyLiteral() {
        assertEquals("\"\"", JsInputLiteral.encode(""))
    }

    /** The literal must be exactly one quoted region: no unescaped quote inside. */
    private fun assertSingleQuotedLiteral(literal: String) {
        assertTrue("must start with a quote", literal.startsWith("\""))
        assertTrue("must end with a quote", literal.endsWith("\""))
        var i = 1
        val end = literal.length - 1
        while (i < end) {
            val c = literal[i]
            if (c == '\\') {
                i += 2
                continue
            }
            assertTrue("unescaped quote at $i in: $literal", c != '"')
            i++
        }
    }
}
