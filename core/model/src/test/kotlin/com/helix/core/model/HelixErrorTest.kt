package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HelixErrorTest {
    private fun error(details: Map<String, String> = linkedMapOf("z_key" to "z", "a_key" to "a")) =
        HelixError(
            code = ErrorCode.PROVIDER_RATE_LIMIT,
            userMessage = "The model provider is rate limited. Try again later.",
            retryable = true,
            safeDetails = details,
            correlationId = CorrelationId("corr-42"),
        )

    @Test
    fun rejectsBlankOversizedUserMessage() {
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "   ", false, emptyMap(), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "x".repeat(513), false, emptyMap(), CorrelationId("c"))
        }
    }

    @Test
    fun rejectsUnsafeSafeDetails() {
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("a b" to "v"), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("" to "v"), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to "x".repeat(1025)), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to "a" + 0x01.toChar() + "b"), CorrelationId("c"))
        }
        val tooMany = (1..17).associate { "k$it" to "v" }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, tooMany, CorrelationId("c"))
        }
        // Documented value contract (HXA-010): 1..1024, no whitespace, no control characters.
        // Empty values, whitespace (space / tab), DEL and C1 controls must all be rejected.
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to ""), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to "a b"), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to "a\tb"), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to "a" + 0x7F.toChar() + "b"), CorrelationId("c"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to "a" + 0x85.toChar() + "b"), CorrelationId("c"))
        }
        // Key length boundary: 64 is legal, 65 is not (HXA-010: 键 1..64).
        assertThrows<IllegalArgumentException> {
            HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k".repeat(65) to "v"), CorrelationId("c"))
        }
        HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k".repeat(64) to "v"), CorrelationId("c"))
        HelixError(ErrorCode.INTERNAL, "m", false, mapOf("k" to "v".repeat(1024)), CorrelationId("c"))
    }

    @Test
    fun storageEncodingSortsDetailsAndRoundTrips() {
        val encoded = error().toStorageString()
        val expected =
            """{"code":"PROVIDER_RATE_LIMIT","userMessage":"The model provider is rate limited. Try again later.",""" +
                """"retryable":true,"safeDetails":{"a_key":"a","z_key":"z"},"correlationId":"corr-42"}"""
        assertEquals(expected, encoded)
        val parsed = HelixError.parse(encoded)
        assertEquals(error(), parsed)
        // Deterministic encoding.
        assertEquals(encoded, error().toStorageString())
    }

    @Test
    fun storageEncodingRoundTripsEmptyDetails() {
        val source = error(emptyMap())
        val encoded = source.toStorageString()
        assertTrueDetailsEmpty(encoded)
        assertEquals(source, HelixError.parse(encoded))
    }

    @Test
    fun parseRejectsMalformedInput() {
        val valid = error().toStorageString()
        assertThrows<IllegalArgumentException> { HelixError.parse("") }
        assertThrows<IllegalArgumentException> {
            HelixError.parse(valid.replace("PROVIDER_RATE_LIMIT", "NOT_A_CODE"))
        }
        assertThrows<IllegalArgumentException> {
            HelixError.parse(valid.dropLast(1) + ",\"extra\":1}")
        }
        assertThrows<IllegalArgumentException> { HelixError.parse(valid.replace(":true", ":123")) }
    }

    private fun assertTrueDetailsEmpty(encoded: String) {
        org.junit.Assert.assertTrue(encoded.contains("\"safeDetails\":{},"))
    }
}
