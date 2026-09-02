package com.helix.runtime.quickjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.nio.charset.StandardCharsets

/**
 * HXA-054 M5 attack and end-to-end suite — roadmap item 6 (Binder large input).
 *
 * Boundary round trips through the REAL client→service chain (values verified against
 * host-side computation), the pre-bind size rejection above the default input limit
 * (no isolated instance spawned), and the service-side fail-closed backstop for
 * inline payloads above the parcel cap without a PFD channel (the client always moves
 * such payloads to PFDs, so only a direct binder user can reach this branch — the
 * defense-in-depth check must reject, never execute and never truncate).
 *
 * Every wait is bounded; the class-level [Timeout] rule is the last-resort
 * anti-hang guard.
 */
class JsBinderLargeInputE2eTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(600)

    private val support = JsExecutionTestSupport

    @Test
    fun twoMiBInputBoundaryRoundTripsVerified() {
        // Input of EXACTLY 2 MiB (the §4.1 maxInputBytes boundary) — above the 64 KiB
        // inline parcel cap, so it travels through a read-only PFD. The payload is a
        // letter pattern so a corrupted round trip (wrong bytes, truncation, shift)
        // would change the returned tail, which the test verifies host-side.
        val q = 34.toChar()
        val n = TWO_MIB - FRAME_BYTES
        // data[i] = pattern[i % 26]; built as a few large repeats (NOT 2M per-char
        // strings — the API 29 instrumentation process OOMEs on that allocation churn).
        val data = PATTERN.repeat(n / PATTERN.length) + PATTERN.take(n % PATTERN.length)
        val input = "{" + q + "data" + q + ":" + q + data + q + "}"
        val inputBytes = input.toByteArray(StandardCharsets.UTF_8)
        assertEquals("the input document must be exactly 2 MiB", TWO_MIB.toLong(), inputBytes.size.toLong())
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-2mib"),
                    source = SRC_TAIL_64,
                    inputJsonUtf8 = inputBytes,
                ),
            )
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        val tail = data.substring(n - TAIL_LEN)
        val expected = "{\"len\":" + n + ",\"tail\":\"" + tail + "\"}"
        assertEquals(
            "the 2 MiB payload must round-trip with verified content",
            expected,
            result.outputUtf8.toString(StandardCharsets.UTF_8),
        )
        // The input artifact hash is independently recomputed on the host side.
        assertEquals(JsHash.sha256Hex(inputBytes), result.inputSha256Hex)
        assertEquals(JsHash.sha256Utf8(expected), result.outputSha256Hex)
    }

    @Test
    fun twoHundredFiftySixKibSourceBoundaryRoundTripsVerified() {
        // Source of EXACTLY 256 KiB (the §4.1 maxSourceBytes boundary) — above the
        // inline parcel cap, so it travels through a read-only PFD. The source is a
        // string literal whose LENGTH is returned: a truncated PFD read (or any byte
        // loss) would break the literal and fail closed instead of executing a
        // silently corrupted program.
        val prefix = "return { n: \""
        val suffix = "\".length }"
        val n = TWO_256_KIB - prefix.length - suffix.length
        val source = prefix + "x".repeat(n) + suffix
        assertEquals(
            "the source must be exactly 256 KiB",
            TWO_256_KIB.toLong(),
            source.toByteArray(StandardCharsets.UTF_8).size.toLong(),
        )
        val result = support.client.execute(support.params(support.newExecutionId("e2e-256ksrc"), source))
        assertEquals(JsExecutionStatus.SUCCESS, result.status)
        assertEquals(
            "the full source must round-trip with verified content (the literal's length)",
            "{\"n\":" + n + "}",
            result.outputUtf8.toString(StandardCharsets.UTF_8),
        )
        assertEquals(JsHash.sha256Hex(ByteArray(0)), result.inputSha256Hex)
    }

    @Test
    fun validInputAboveDefaultLimitIsRejectedPreBind() {
        // A VALID 3 MiB JSON input (the HXA-051 case used non-JSON bytes, which the
        // JSON preflight rejects first): the SIZE branch of the client preflight must
        // reject it with the size reason, before any bind — no isolated instance.
        val q = 34.toChar()
        val n = THREE_MIB - FRAME_BYTES
        val input = "{" + q + "data" + q + ":" + q + "a".repeat(n) + q + "}"
        val result =
            support.client.execute(
                support.params(
                    executionId = support.newExecutionId("e2e-3mib"),
                    source = "return 1 + 1",
                    inputJsonUtf8 = input.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        assertEquals(JsExecutionStatus.REQUEST_REJECTED, result.status)
        assertTrue(
            "the rejection must name the input size limit, got: ${result.detail}",
            result.detail.contains("exceeds maxInputBytes"),
        )
        assertEquals("rejection is pre-bind: no isolated instance is spawned", -1, result.servicePid)
        assertEquals(-1, result.serviceUid)
    }

    @Test
    fun inlinePayloadAboveParcelCapFailsClosedAtService() {
        // The client always moves >64 KiB payloads to PFDs; the service-side inline-cap
        // check is the defense-in-depth backstop for direct binder users that bypass
        // the client. A direct EXECUTE carrying an inline payload above the parcel cap
        // (and no PFD channel) must fail CLOSED — REQUEST_REJECTED demanding a PFD —
        // never execute and never silently truncate.
        val sourceInstance = support.bindDirect(support.newInstanceName("e2e-inlinesrc"))
        try {
            val overSource =
                support.executeDirect(
                    sourceInstance.binder,
                    support.newExecutionId("e2e-inlinesrc-ex"),
                    "x".repeat(100 * 1024),
                )
            assertEquals(JsExecutionStatus.REQUEST_REJECTED, overSource.status)
            assertTrue(
                "the rejection must demand a source PFD, got: ${overSource.detail}",
                overSource.detail.contains("source PFD"),
            )
        } finally {
            sourceInstance.release()
        }
        val inputInstance = support.bindDirect(support.newInstanceName("e2e-inlinein"))
        try {
            val overInput =
                support.executeDirect(
                    inputInstance.binder,
                    support.newExecutionId("e2e-inlinein-ex"),
                    "return 1 + 1",
                    inputJsonUtf8 = "a".repeat(100 * 1024).toByteArray(StandardCharsets.UTF_8),
                )
            assertEquals(JsExecutionStatus.REQUEST_REJECTED, overInput.status)
            assertTrue(
                "the rejection must demand an input PFD, got: ${overInput.detail}",
                overInput.detail.contains("input PFD"),
            )
        } finally {
            inputInstance.release()
        }
    }

    companion object {
        private const val TWO_MIB: Int = 2 * 1024 * 1024

        private const val THREE_MIB: Int = 3 * 1024 * 1024

        private const val TWO_256_KIB: Int = 256 * 1024

        private const val FRAME_BYTES: Int = 11 // {"data":"..."}

        private const val TAIL_LEN: Int = 64

        private const val PATTERN: String = "abcdefghijklmnopqrstuvwxyz"

        private const val SRC_TAIL_64 = "return { len: input.data.length, tail: input.data.slice(-64) }"
    }
}
