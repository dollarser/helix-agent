package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * HXA-052 client output contract (doc 03 §4.6): a SUCCESS output is exactly one JSON
 * document within maxOutputBytes; anything else is a stable rejection — no truncation,
 * no raw-text/base64 fallback.
 */
class JsOutputContractTest {
    @Test
    fun validJsonDocumentsPass() {
        listOf("{}", """{"a":1,"b":[1,2,3]}""", "7", "-0.5", "true", "null", "\"text\"", "\"unicode 🚀\"")
            .forEach { document ->
                assertNull(
                    "expected pass for $document",
                    JsOutputContract.validate(document.toUtf8(), 256 * 1024),
                )
            }
    }

    @Test
    fun nonJsonDocumentsAreRejectedWithAStableReason() {
        // Raw text, base64 blobs and bare JS values are NOT valid JSON documents — and
        // the client must not fall back to interpreting them.
        listOf(
            "",
            "   ",
            "not json",
            "aGVsbG8=",
            "undefined",
            "NaN",
            "{a: 1}",
            "7x",
            """{"a":1} trailing""",
        ).forEach { bad ->
            val reason = JsOutputContract.validate(bad.toUtf8(), 256 * 1024)
            assertTrue(
                "expected rejection for: $bad",
                reason != null && reason.contains("not a valid JSON document"),
            )
        }
    }

    @Test
    fun quotedStringDocumentsAreAccepted() {
        // A JSON string is a legal document (the wrapper's output for `return "…"`);
        // the ambiguous double-encoding case is prevented at the source (byte-for-byte
        // pass-through encoder, pinned in JsResultJsonEncoderTest), not here.
        assertNull(JsOutputContract.validate("\"7\"".toUtf8(), 256 * 1024))
        assertNull(JsOutputContract.validate("\"\"".toUtf8(), 256 * 1024))
    }

    @Test
    fun sizeIsCheckedInTheUtf8Bytes() {
        val max = 100
        assertEquals(
            "output 101 bytes exceeds maxOutputBytes 100",
            JsOutputContract.validate("1".repeat(101).toUtf8(), max),
        )
        // Exactly at the bound passes (a 100-digit number is valid JSON).
        assertNull(JsOutputContract.validate("1".repeat(100).toUtf8(), max))
        // Multi-byte: 50 CJK chars = 150 UTF-8 bytes > 100 even though "length" is 50.
        assertTrue(
            JsOutputContract.validate("汉".repeat(50).toUtf8(), max) != null,
        )
    }

    @Test
    fun nonUtf8OutputIsRejected() {
        assertTrue(
            (JsOutputContract.validate(byteArrayOf(0x41, 0xFF.toByte()), 256 * 1024) ?: "")
                .contains("not a valid JSON document"),
        )
    }

    private fun String.toUtf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
