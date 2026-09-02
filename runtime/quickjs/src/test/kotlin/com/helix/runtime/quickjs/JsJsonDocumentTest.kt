package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * HXA-052 strict JSON document validation (RFC 8259 grammar, fail-closed): the client
 * input pre-check, the service payload re-check and the client output contract all rely
 * on this single pure-JVM validator.
 */
class JsJsonDocumentTest {
    @Test
    fun validDocumentsAreAccepted() {
        listOf(
            "null",
            "true",
            "false",
            "0",
            "-0",
            "1",
            "1.0",
            "1e5",
            "-2.5E+3",
            "123.456e-7",
            "[]",
            "{}",
            "[1,2,3]",
            """{"a":1}""",
            "  [ 1 , { \"b\" : null } ]  ",
            "\"\"",
            "\"abc\"",
            "\"a\\u0041b\"", // \u escape, BMP
            "\"\\ud83d\\ude00\"", // \u escape, surrogate pair
            "\"\\ud800\"", // lone surrogate half: legal JSON text, accepted
            "\"tab\\there\"",
            "\"slash\\/here\"",
            "\"quote\\\"here\"",
        ).forEach { document ->
            assertTrue("expected valid JSON: $document", JsJsonDocument.isValidJson(document.toUtf8()))
        }
    }

    @Test
    fun invalidDocumentsAreRejected() {
        listOf(
            "",
            "   ",
            "1 2",
            "1-",
            "{1:2}",
            """{"a"}""",
            """{"a":1,""}""",
            """{"a":1,}""",
            "[1,]",
            "[1 2]",
            "tru",
            "nullx",
            "faulse",
            "NaN",
            "Infinity",
            "-Infinity",
            "01",
            "1.",
            ".5",
            "-",
            "1e",
            "1e+",
            "0x1",
            "1_000",
            "-.5",
            "\"abc", // unterminated
            "\"a\nb\"", // raw control inside a string
            "\"a\\qb\"", // bad escape
            "\"\\u12g\"", // bad hex
            "\"\\u12\"", // too short
            "\"\\x41\"", // not a JSON escape
            "['a',,]",
        ).forEach { document ->
            assertFalse(
                "expected invalid JSON: ${document.replace("\n", "\\n")}",
                JsJsonDocument.isValidJson(document.toUtf8()),
            )
        }
    }

    @Test
    fun deepNestingIsAcceptedUpToTheCapAndRejectedAbove() {
        val deepValid = "[".repeat(512) + "]".repeat(512)
        val deepInvalid = "[".repeat(513) + "]".repeat(513)
        val objectDeepValid = """{"a":""".repeat(300) + "1" + "}".repeat(300)
        assertTrue(JsJsonDocument.isValidJson(deepValid.toUtf8()))
        assertFalse(JsJsonDocument.isValidJson(deepInvalid.toUtf8()))
        assertTrue(JsJsonDocument.isValidJson(objectDeepValid.toUtf8()))
        assertEquals(512, JsJsonDocument.MAX_DEPTH)
    }

    @Test
    fun invalidUtf8IsRejected() {
        assertFalse(JsJsonDocument.isValidJson(byteArrayOf(0xFF.toByte(), 0x28)))
        // Truncated emoji (first three bytes of a 4-byte sequence).
        val emoji = "\ud83d\ude00".toUtf8()
        assertFalse(JsJsonDocument.isValidJson(emoji.copyOfRange(0, 3)))
    }

    @Test
    fun strictDecodeRoundTripsAndRejects() {
        val text = "héllo \ud83d\ude00"
        assertEquals(text, JsJsonDocument.decodeUtf8Strict(text.toUtf8()))
        assertNull(JsJsonDocument.decodeUtf8Strict(byteArrayOf(0x41, 0x9F.toByte())))
    }

    @Test
    fun duplicateKeysAreAcceptedPerRfc8259() {
        assertTrue(JsJsonDocument.isValidJson("""{"a":1,"a":2}""".toUtf8()))
    }

    private fun String.toUtf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
