package com.helix.runtime.proot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BridgeOutputRedactorTest {
    @Test fun cleanEofPreservesOrdinaryTextThatOnlySharesAShortProofPrefix() {
        val capture = BoundedCapture(OutputBudget(100)).hideProof("a".repeat(64))
        capture.drain(ByteArrayInputStream("ordinary data".toByteArray()))
        capture.finish()
        assertEquals("ordinary data", capture.bytes.decodeToString())
    }

    @Test fun everyChunkBoundaryAndTruncatedPrefixStayOutOfCaptureAndLiveLog() {
        val secret = "0123456789abcdef".repeat(4)
        for (split in 0..secret.length) {
            val output = java.io.ByteArrayOutputStream()
            val filter = BridgeOutputRedactor(secret) { bytes, count -> output.write(bytes, 0, count) }
            val first = ("start:" + secret.take(split)).toByteArray()
            val second = (secret.drop(split) + ":end").toByteArray()
            filter.write(first, first.size)
            filter.write(second, second.size)
            filter.finish()
            assertEquals("start:[redacted]:end", output.toString())
        }
        val log = java.io.ByteArrayOutputStream()
        val capture = BoundedCapture(OutputBudget(20), { bytes, count -> log.write(bytes, 0, count) }).hideProof(secret)
        capture.drain(ByteArrayInputStream(secret.toByteArray()))
        capture.finish()
        assertTrue(capture.truncated)
        assertEquals("[redacted]", capture.bytes.decodeToString())
        assertEquals("[redacted]", log.toString())
    }
}
