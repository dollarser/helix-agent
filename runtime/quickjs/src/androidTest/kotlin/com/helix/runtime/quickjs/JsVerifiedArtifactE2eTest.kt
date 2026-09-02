package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * HXA-054 M5 attack and end-to-end suite — roadmap item 8 (verified artifact) plus the
 * architecture doc §10 QuickJS remainder (Unicode/NUL, deep JSON nesting, circular
 * objects) that HXA-052 did not pin from the output direction.
 *
 * Verified artifact: the client accepts a SUCCESS result only after verifying the
 * materialized output (size + SHA-256 recomputed HOST-SIDE + output contract). This
 * class is the E2E proof on the normal path: the TEST independently recomputes the
 * SHA-256 of the output file bytes and of the input bytes and asserts they match the
 * result's declarations — the host-side landing point of the auditable artifact
 * (doc 03 §4.8). The mismatch branches (hash/size/contract) are covered on the JVM
 * against the pure [JsOutputArtifact] verification gate (JsOutputArtifactTest) — the
 * real service never declares a hash that doesn't match the bytes it wrote, so the
 * device chain cannot manufacture them and no new production debug seam is added.
 *
 * Every wait is bounded; the class-level [Timeout] rule is the last-resort
 * anti-hang guard.
 */
class JsVerifiedArtifactE2eTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(600)

    private val support = JsExecutionTestSupport

    @Test
    fun verifiedArtifactIsRecomputedAndAcceptedHostSide() {
        // 200 KiB output (above the 64 KiB inline cap → PFD channel): the client
        // re-materializes the file and accepts the result only after the verified
        // artifact passes. The test recomputes every hash independently.
        val q = 34.toChar()
        val input = "{" + q + "s" + q + ":" + q + "hello artifact" + q + "}"
        val inputBytes = input.toByteArray(StandardCharsets.UTF_8)
        val outputFile = File(support.context.cacheDir, "js-e2e-verified-${System.nanoTime()}.out")
        try {
            val result =
                support.client.execute(
                    support.params(
                        executionId = support.newExecutionId("e2e-verified"),
                        source = RETURN_200KIB,
                        inputJsonUtf8 = inputBytes,
                        outputFile = outputFile,
                    ),
                )
            assertEquals(JsExecutionStatus.SUCCESS, result.status)
            assertEquals(200L * 1024, result.outputBytes)
            val fileBytes = outputFile.readBytes()
            // Host-side recompute: the artifact the client accepted is byte-identical
            // to the file, and its SHA-256 matches the result declaration.
            assertEquals(fileBytes.size.toLong(), result.outputBytes)
            assertEquals(
                "the host-side recomputed SHA-256 of the output file must match the declaration",
                JsHash.sha256Hex(fileBytes),
                result.outputSha256Hex,
            )
            assertEquals(
                fileBytes.toString(StandardCharsets.UTF_8),
                result.outputUtf8.toString(StandardCharsets.UTF_8),
            )
            // The input artifact hash is independently verifiable on the host side too.
            assertEquals(JsHash.sha256Hex(inputBytes), result.inputSha256Hex)
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun nulAndCombinedEmojiRoundTripInOutput() {
        // doc 03 §10: the INPUT direction (NUL + emoji round trip) is pinned by
        // HXA-052's jsonRoundTripWithControlAndUnicode; here the OUTPUT direction: a
        // NUL byte and a ZWJ-combined emoji must survive the JSON.stringify → wire →
        // client re-validation chain byte-exact — NUL as the strict \u0000 escape, the
        // emoji as raw UTF-8 (JSON.stringify escapes only control chars, quote and
        // backslash).
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-nulemo"),
                    source = "return \"a\" + String.fromCharCode(0) + \"b\" + \"\uD83D\uDC68\u200D\uD83D\uDC69\"",
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        val expected = "\"a\\u0000b\uD83D\uDC68\u200D\uD83D\uDC69\""
        assertEquals(expected, result.outputUtf8.toString(StandardCharsets.UTF_8))
        assertEquals(JsHash.sha256Utf8(expected), result.outputSha256Hex)
    }

    @Test
    fun deepNestingRoundTripInOutput() {
        // doc 03 §10: the INPUT direction (300-deep round trip) is pinned by HXA-052's
        // deepNestingRoundTrip; here the OUTPUT direction: a 200-deep object built in
        // JS must stringify to the exact document, value-verified against a host-side
        // construction (any reordering or loss changes the document).
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-deepout"),
                    source = "var o = { v: 42 }; for (var i = 0; i < 200; i++) o = { c: o }; return o",
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        var expected = "{\"v\":42}"
        repeat(200) { expected = "{\"c\":$expected}" }
        assertEquals(expected, result.outputUtf8.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun circularResultFailsClosedAsOutputLimit() {
        // doc 03 §10: a cyclic structure is not JSON-encodable. The wrapper's
        // stringify step sits OUTSIDE the user-error try/catch, so the engine's
        // TypeError propagates raw (unprefixed) and the service reclassifies the
        // circular marker as OUTPUT_LIMIT (the HXA-051 "result not JSON-encodable"
        // semantics) — never a success, never a crash.
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-circ"),
                    source = "var o = { v: 1 }; o.self = o; return o",
                ),
            )
        assertEquals(JsExecutionStatus.OUTPUT_LIMIT, result.status)
        assertTrue(
            "detail must carry the engine circular marker, got: ${result.detail}",
            result.detail.contains(JsAbiAssembly.CIRCULAR_RESULT_MARKER),
        )
        // Recovery: a fresh instance runs normally after the encoding failure.
        val next = support.client.execute(support.params(support.newExecutionId("e2e-circ-next"), "return 1 + 1"))
        assertEquals(JsExecutionStatus.SUCCESS, next.status)
        assertEquals("2", next.outputUtf8.toString(Charsets.UTF_8))
    }

    companion object {
        // Document = 2 quotes + 204798 chars = exactly 200 KiB.
        private const val RETURN_200KIB = "return \"v\".repeat(200 * 1024 - 2)"
    }
}
