package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * HXA-054 (JVM): the verified-artifact verification branches (doc 03 §4.6/§4.8).
 *
 * [JsOutputArtifact] is the client's acceptance gate for a SUCCESS result: exact
 * size + host-side-recomputed SHA-256 + the HXA-052 output contract. The mismatch
 * branches below are the JVM injection-point coverage for the "hash/size mismatch →
 * UNKNOWN, never accepted" requirement: the real service never declares a hash that
 * doesn't match the bytes it wrote, so the device chain cannot manufacture a
 * mismatch and no new production debug seam is added — the gate is a pure function
 * and is tested here directly.
 */
class JsOutputArtifactTest {
    private val maxOutput = 256 * 1024

    @Test
    fun matchingArtifactVerifies() {
        val bytes = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
        assertNull(JsOutputArtifact.verify(bytes, bytes.size.toLong(), JsHash.sha256Hex(bytes), maxOutput))
    }

    @Test
    fun sizeMismatchFails() {
        val bytes = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
        val reason = JsOutputArtifact.verify(bytes, bytes.size.toLong() + 1, JsHash.sha256Hex(bytes), maxOutput)
        assertNotNull("a declared size that doesn't match the bytes must fail", reason)
        assertTrue("the reason must name the size mismatch, got: $reason", reason!!.contains("size"))
    }

    @Test
    fun declaredShorterThanArtifactFails() {
        val bytes = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
        val reason = JsOutputArtifact.verify(bytes, 1L, JsHash.sha256Hex(bytes), maxOutput)
        assertNotNull("a truncated artifact (declared > actual) must fail", reason)
        assertTrue("the reason must name the size mismatch, got: $reason", reason!!.contains("size"))
    }

    @Test
    fun hashMismatchFails() {
        val bytes = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
        val otherHash = JsHash.sha256Hex("""{"ok":false}""".toByteArray(StandardCharsets.UTF_8))
        val reason = JsOutputArtifact.verify(bytes, bytes.size.toLong(), otherHash, maxOutput)
        assertNotNull("a SHA-256 that doesn't match the bytes must fail", reason)
        assertTrue("the reason must name the SHA-256 mismatch, got: $reason", reason!!.contains("SHA-256"))
    }

    @Test
    fun contractViolationFails() {
        // Valid size + matching hash, but the payload is not exactly one JSON document
        // (raw text): the output contract rejects it — never accepted, never
        // truncated, never re-interpreted as raw text.
        val bytes = "not json at all".toByteArray(StandardCharsets.UTF_8)
        val reason = JsOutputArtifact.verify(bytes, bytes.size.toLong(), JsHash.sha256Hex(bytes), maxOutput)
        assertNotNull("a non-JSON payload must fail the output contract", reason)
        assertTrue(
            "the reason must name the contract violation, got: $reason",
            reason!!.contains("output contract violation"),
        )
    }

    @Test
    fun overLimitArtifactFails() {
        // Exactly one byte over maxOutputBytes: the contract's byte bound rejects even
        // with a matching declaration (the size check passes, the byte cap catches it).
        val bytes = ByteArray(maxOutput + 1) { 'a'.code.toByte() }
        val reason = JsOutputArtifact.verify(bytes, bytes.size.toLong(), JsHash.sha256Hex(bytes), maxOutput)
        assertNotNull("an artifact one byte over the limit must fail", reason)
        assertTrue(
            "the reason must name the maxOutputBytes cap, got: $reason",
            reason!!.contains("exceeds maxOutputBytes"),
        )
    }

    @Test
    fun boundaryArtifactVerifies() {
        // A JSON document of EXACTLY maxOutputBytes bytes: the boundary is inclusive.
        val q = 34.toChar()
        val bytes = (q + "a".repeat(maxOutput - 2) + q).toByteArray(StandardCharsets.UTF_8)
        assertEquals(maxOutput.toLong(), bytes.size.toLong())
        assertNull(JsOutputArtifact.verify(bytes, bytes.size.toLong(), JsHash.sha256Hex(bytes), maxOutput))
    }
}
