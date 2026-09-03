package com.helix.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * HXA-058: [SafExportVerifier] — the post-export re-read verification. Only a complete, exact,
 * hash-equal re-read yields verified=true; any missing or mismatching evidence yields false
 * (the export then reports only the platform-confirmed result).
 */
class SafExportVerifierTest {
    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun reReader(bytes: ByteArray?) =
        SafDestinationReReader {
            if (bytes ==
                null
            ) {
                null
            } else {
                ByteArrayInputStream(bytes)
            }
        }

    @Test
    fun anExactReReadIsVerified() {
        val bytes = "exported bytes\n".toByteArray()
        assertTrue(SafExportVerifier.reReadVerified("uri", reReader(bytes), hex(bytes), bytes.size.toLong()))
    }

    @Test
    fun anEmptyFileIsVerifiedWhenTheReReadEndsExactlyAtEof() {
        val sha = hex(ByteArray(0))
        assertTrue(SafExportVerifier.reReadVerified("uri", reReader(ByteArray(0)), sha, 0L))
    }

    @Test
    fun aMismatchedReReadIsNotVerified() {
        val bytes = "exported bytes\n".toByteArray()
        val other = "TAMPERED BYTES!\n".toByteArray() // same length, different bytes
        assertFalse(SafExportVerifier.reReadVerified("uri", reReader(other), hex(bytes), bytes.size.toLong()))
    }

    @Test
    fun aTruncatedReReadIsNotVerified() {
        val bytes = "exported bytes\n".toByteArray()
        assertFalse(SafExportVerifier.reReadVerified("uri", reReader(bytes.copyOf(4)), hex(bytes), bytes.size.toLong()))
    }

    @Test
    fun anExtraByteOnTheDestinationIsNotVerified() {
        val bytes = "exported bytes\n".toByteArray()
        val withExtra = bytes + ByteArray(1) { 0x41 }
        assertFalse(
            "a destination holding MORE than the written bytes is not verified",
            SafExportVerifier.reReadVerified("uri", reReader(withExtra), hex(bytes), bytes.size.toLong()),
        )
    }

    @Test
    fun anUnreadableDestinationIsNotVerified() {
        val bytes = "x".toByteArray()
        assertFalse(SafExportVerifier.reReadVerified("uri", reReader(null), hex(bytes), bytes.size.toLong()))
    }

    @Test
    fun aReReadIOExceptionIsNotVerified() {
        val bytes = "x".toByteArray()
        val throwing = SafDestinationReReader { throw IOException("provider reset") }
        assertFalse(SafExportVerifier.reReadVerified("uri", throwing, hex(bytes), bytes.size.toLong()))
    }

    @Test
    fun missingExpectedEvidenceIsNotVerified() {
        val bytes = "x".toByteArray()
        assertFalse(
            "a null expected hash never verifies",
            SafExportVerifier.reReadVerified("uri", reReader(bytes), null, bytes.size.toLong()),
        )
        assertFalse(
            "a negative expected size never verifies",
            SafExportVerifier.reReadVerified("uri", reReader(bytes), hex(bytes), -1L),
        )
    }

    @Test
    fun aLargeReReadStaysChunkedAndVerified() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        assertTrue(SafExportVerifier.reReadVerified("uri", reReader(bytes), hex(bytes), bytes.size.toLong()))
        assertEquals(300_000L, bytes.size.toLong())
    }
}
