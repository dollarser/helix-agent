package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Exercises evidence infrastructure only: no provider, account, oracle or model calls. */
class AndroidWorldEvidenceDeviceTest {
    @Test
    fun atomicReplacementAndFailedPublicationPreserveEvidence() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "androidworld-evidence-").toFile()
        try {
            val destination = File(directory, "attempt.json")
            AndroidWorldEvidenceFile.publish(destination, "{\"stage\":\"start\"}")
            AndroidWorldEvidenceFile.publish(destination, "{\"stage\":\"cleanup\"}")
            val previous = destination.readText()
            assertEquals("{\"stage\":\"cleanup\"}", previous)
            assertTrue(runCatching { AndroidWorldEvidenceFile.publish(destination, "oversized", 2) }.isFailure)
            assertEquals(previous, destination.readText())
            val blocked = File(directory, "blocked.json")
            assertTrue(blocked.mkdir())
            File(blocked, "kept").writeText("kept")
            assertTrue(runCatching { AndroidWorldEvidenceFile.publish(blocked, "replacement") }.isFailure)
            assertEquals("kept", File(blocked, "kept").readText())
            assertFalse(requireNotNull(directory.listFiles()).any { it.name.endsWith(".tmp") })
        } finally {
            check(directory.deleteRecursively()) { "Could not clean owned evidence test directory" }
        }
    }

    @Test
    fun diagnosticsStayBoundedAndDoNotRenderThrowable() {
        val helper = BrowserBoundedFailureEvidence
        val failure =
            object : IllegalStateException("秘密😀".repeat(20_000)) {
                override fun toString(): String = error("Throwable rendering must never be called")
            }
        val diagnostic = helper.diagnostic(failure)
        assertTrue(diagnostic.truncated)
        assertTrue(diagnostic.text.toByteArray(Charsets.UTF_8).size <= helper.DIAGNOSTIC_LIMIT_BYTES)
        assertTrue(helper.message(failure).length <= helper.MESSAGE_LIMIT)
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        first.initCause(second)
        second.initCause(first)
        assertTrue(helper.diagnostic(first).truncated)
        assertTrue("cause cycle omitted" in helper.diagnostic(first).text)
        val sanitized = helper.message(IllegalStateException("Authorization: Bearer abc123; password=secret456"))
        assertFalse("abc123" in sanitized)
        assertFalse("secret456" in sanitized)
    }
}
