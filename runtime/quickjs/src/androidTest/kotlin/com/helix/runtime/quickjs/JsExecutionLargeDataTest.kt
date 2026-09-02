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
 *
 * HXA-052 ABI: the input bytes must be a valid JSON document (the wrapper parses them);
 * a top-level source expression is now a helixMain BODY and the SUCCESS output is the
 * wrapper's JSON.stringify text — a JSON document (strings quoted, numbers bare).
 */
class JsExecutionLargeDataTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(240)

    private val support = JsExecutionTestSupport

    @Test
    fun largeInputViaReadonlyPfdIsHashedAndExecuted() {
        // 1 MiB input > 64 KiB inline cap → the client moves it to a read-only PFD. The
        // service reads it back, enforces maxInputBytes and reports its SHA-256 (doc 03
        // §4.8 audit field). The input is a JSON object carrying the 1 MiB payload.
        // (quote char built from its code point: raw-string quote runs are ambiguous
        // in Kotlin and this document's validity is asserted by the client preflight)
        val q = 34.toChar()
        val payload = "a".repeat(1 shl 20)
        val input = "{" + q + "data" + q + ":" + q + payload + q + "}"
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("biginput"),
                    source = "return { len: input.data.length }",
                    inputJsonUtf8 = input.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(ONE_MIB_LEN_JSON, result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Hex(input.toByteArray(StandardCharsets.UTF_8)), result.inputSha256Hex)
    }

    @Test
    fun largeSourceViaReadonlyPfdIsExecuted() {
        // ~100 KiB source > 64 KiB inline cap (still under the 256 KiB limit) → source PFD.
        val source = RETURN_OK_COMMENT + "x".repeat(33_000)
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("bigsource"),
                    source = source,
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        // The wrapper stringifies the returned value: "ok" becomes the JSON string doc.
        // Returning it proves the FULL source (including the 100 KiB padded tail, kept
        // alive as a trailing comment) was delivered through the PFD and executed.
        assertEquals(QUOTED_OK, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun largeOutputViaWritablePfdIsDelivered() {
        // 128 KiB output > 64 KiB inline cap → the service writes it through the
        // caller-provided writable PFD; the client re-materializes and verifies it.
        // The JSON document is exactly 131 072 bytes (128 KiB): quote + 131 070 chars
        // + quote — exactly at maxOutputBytes (the boundary is inclusive).
        val q = 34.toChar()
        val expected = q + "b".repeat(128 * 1024 - 2) + q
        val outputFile = File(support.context.cacheDir, "js-exec-test-${System.nanoTime()}.out")
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("bigoutput"),
                    source = RETURN_B_REPEAT,
                    outputFile = outputFile,
                ),
            )
        try {
            assertEquals(JsExecutionStatus.SUCCESS, result.status)
            assertEquals(128L * 1024, result.outputBytes)
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
        // Empty input bytes = absent input → the wrapper's null literal (JSON.parse(null)
        // → null). The input hash is the stable empty-input SHA-256.
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("emptyinput"),
                    source = "return 1",
                    inputJsonUtf8 = ByteArray(0),
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        // JSON.stringify(1) is the JSON number document `1` (numbers stay bare).
        assertEquals("1", result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Hex(ByteArray(0)), result.inputSha256Hex)
    }

    companion object {
        private const val ONE_MIB_LEN_JSON = """{"len":1048576}"""
        private const val RETURN_OK_COMMENT = """return "ok" // """

        // The wrapper stringifies the returned string: quoted JSON string document.
        private const val QUOTED_OK = """"ok""""
        private const val RETURN_B_REPEAT = """return "b".repeat(128 * 1024 - 2)"""
    }
}
