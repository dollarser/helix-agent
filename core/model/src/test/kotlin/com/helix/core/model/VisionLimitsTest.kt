package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-055: [VisionLimits] — the centralized, fail-closed vision budget. The invariants under
 * test are the STRICTER-OF chains pinned to the measured evidence (see the config's KDoc):
 * - the per-image raw budget base64-encodes strictly under the per-image wire budget;
 * - the per-request total is the per-message image cap times the per-image wire budget;
 * - the decode envelope (edge/pixels) admits what the normalizer may allocate on-device.
 */
class VisionLimitsTest {
    private fun base64Length(rawBytes: Long): Long = ((rawBytes + 2L) / 3L) * 4L

    @Test
    fun theNormalizedRawBudgetStaysUnderThePerImageWireBudget() {
        // 2_850_000 raw bytes base64-encode to exactly 3_800_000 (the closed invariant the
        // constants are built from: wire budget * 3 / 4) — a byte MORE would break it.
        val encoded = base64Length(VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong())
        assertEquals(
            VisionLimits.MAX_BASE64_PER_IMAGE_BYTES.toLong(),
            encoded,
        )
        assertTrue(
            "one byte over the raw budget must exceed the wire budget",
            base64Length(VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong() + 1L) > VisionLimits.MAX_BASE64_PER_IMAGE_BYTES,
        )
    }

    @Test
    fun thePerRequestTotalIsTheMessageCapTimesThePerImageBudget() {
        assertEquals(
            4 * VisionLimits.MAX_BASE64_PER_IMAGE_BYTES,
            VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES,
        )
        // The request total is under the strictest provider request-size bound (Anthropic 32 MB).
        assertTrue(VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES < 32L * 1024 * 1024)
    }

    @Test
    fun theInputBudgetIsTheClosedAttachmentCap() {
        assertEquals(10L shl 20, VisionLimits.MAX_INPUT_BYTES)
    }

    @Test
    fun theClosedMediaTypesMatchTheImageReferenceSet() {
        assertEquals(ImageReference.MEDIA_TYPES, VisionLimits.NORMALIZED_MEDIA_TYPES)
    }

    @Test
    fun edgeFittingIsBoundedByTheLongestEdge() {
        assertTrue(VisionLimits.normalizedEdgeFits(3000, 2000))
        // One pixel over the edge cap is refused even though the pixel total is tiny.
        assertFalse(VisionLimits.normalizedEdgeFits(VisionLimits.MAX_EDGE_PX + 1, 10))
        // A tiny total pixel count never excuses a too-long edge.
        assertFalse(VisionLimits.normalizedEdgeFits(100_000, 2))
    }

    @Test
    fun thePixelTotalCapsTheDecodeEnvelope() {
        // 12 MP is the cap: one step over is refused even though each edge is fine.
        assertTrue(VisionLimits.normalizedEdgeFits(4096, 3000))
        assertFalse(VisionLimits.normalizedEdgeFits(4096, 3073))
    }

    @Test
    fun sampleSizeSelectionIsTheSmallestPowerOfTwoThatFits() {
        // Already fits: no sampling.
        assertEquals(1, VisionLimits.requiredInSampleSize(2048, 1536))
        // 8192x8192: sample 2 → 4096x4096 fits (16 MP > 12 MP at full, so 2 is NOT enough for
        // the PIXEL cap — 4096*4096 = 16.8 MP > 12.3 MP → sample 4 → 2048² = 4.2 MP fits).
        assertEquals(4, VisionLimits.requiredInSampleSize(8192, 8192))
        // 6000x4000 (24 MP): sample 2 → 3000x2000 (6 MP) fits.
        assertEquals(2, VisionLimits.requiredInSampleSize(6000, 4000))
    }

    @Test
    fun anUnfittableImageFailsClosed() {
        // 100_000 x 100_000: even the largest practical sample (128) leaves 781² — under the
        // pixel cap but the edge gate at sample 128 is 781 which fits... the sentinel is only
        // returned when the loop exhausts: 16_000_000 x 16_000_000 / 128² = still > 12 MP.
        assertEquals(
            Int.MAX_VALUE,
            VisionLimits.requiredInSampleSize(16_000_000, 16_000_000),
        )
    }
}
