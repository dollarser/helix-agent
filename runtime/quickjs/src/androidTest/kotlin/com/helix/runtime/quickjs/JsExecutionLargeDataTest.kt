package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * HXA-051 bounded-IPC paths (doc 03 §3.1): payloads above the 64 KiB inline parcel cap
 * travel through `ParcelFileDescriptor`s — read-only for source/input, caller-provided
 * writable for the output. Both directions are exercised end-to-end through the client.
 */
class JsExecutionLargeDataTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(240)

    private val support = JsExecutionTestSupport

    @Test
    fun largeInputViaReadonlyPfdIsHashedAndExecuted() {
        // 1 MiB input > 64 KiB inline cap → the client moves it to a read-only PFD. The
        // service reads it back, enforces maxInputBytes and reports its SHA-256 (doc 03
        // §4.8 audit field). Input injection into JS is HXA-052's wrapper's concern.
        val input =
            "\"".toByteArray(StandardCharsets.UTF_8) + "a".repeat(1 shl 20).toByteArray(StandardCharsets.UTF_8) +
                "\"".toByteArray(StandardCharsets.UTF_8)
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("biginput"),
                    source = "1 + 2",
                    inputJsonUtf8 = input,
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals("3", result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Hex(input), result.inputSha256Hex)
    }

    @Test
    fun largeSourceViaReadonlyPfdIsExecuted() {
        // 100 KiB source > 64 KiB inline cap (still under the 256 KiB limit) → source PFD.
        val source = "1 + 1 // " + "pad".repeat(25_000) // ~100 KiB
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("bigsource"),
                    source = source,
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        // Evaluating to 2 proves the FULL source (including the 100 KiB padding tail)
        // was delivered through the PFD and executed.
        assertEquals("2", result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun largeOutputViaWritablePfdIsDelivered() {
        // 128 KiB output > 64 KiB inline cap → the service writes it through the
        // caller-provided writable PFD; the client re-materializes and verifies it.
        val expected = "b".repeat(128 * 1024)
        val outputFile = File(support.context.cacheDir, "js-exec-test-${System.nanoTime()}.out")
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("bigoutput"),
                    source = "\"b\".repeat(128 * 1024)",
                    outputFile = outputFile,
                ),
            )
        try {
            assertEquals(JsExecutionStatus.SUCCESS, result.status)
            assertEquals(expected.length.toLong(), result.outputBytes)
            assertEquals(expected, result.outputUtf8.toString(StandardCharsets.UTF_8))
            assertEquals(JsHash.sha256Utf8(expected), result.outputSha256Hex)
            // The file itself carries the full payload (PFD path, not inline).
            assertTrue(outputFile.exists())
            assertEquals(expected, outputFile.readText())
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun emptyInputHashIsStable() {
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("emptyinput"),
                    source = "1",
                    inputJsonUtf8 = ByteArray(0),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(JsHash.sha256Hex(ByteArray(0)), result.inputSha256Hex)
    }
}
