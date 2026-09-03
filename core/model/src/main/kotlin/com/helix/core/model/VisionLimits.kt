package com.helix.core.model

/**
 * The centralized, fail-closed budget for image (vision) attachments (HXA-055;
 * ADR-0014 decision 4: "具体像素、边长与请求字节上限 … 取 Helix 上限与 Provider capability
 * 中更严者"). Every value below is pinned to MEASURED evidence, not to a guess:
 *
 * Device evidence (HXA-055 Phase 1, `ImageNormalizationDeviceProbe`, both emulators):
 * - Bitmap pixels live in NATIVE memory, not the JVM heap (the API 29 emulator's 48 MiB
 *   JVM heap still decoded a 32 MP / 128 MiB bitmap). The decode envelope is therefore
 *   bounded by device native memory: measured 1999 MiB total / 216 MiB low-memory
 *   threshold on API 29, 2472 MiB total / 216 MiB threshold on API 36.
 * - `BitmapFactory` does NOT auto-apply EXIF orientation (measured: orientation=6 input
 *   decodes to the stored 1000x500, unrotated) — the normalizer rotates manually.
 * - `Bitmap.compress` re-encode strips ALL EXIF (orientation/GPS/comment) — the privacy
 *   strip is the re-encode itself.
 * - The SOF header is forgeable and (measured) visible to `inJustDecodeBounds` up to
 *   30000x30000 claimed dimensions (65535x65535 is rejected by the platform at the
 *   bounds stage). Header dimensions are therefore NEVER trusted for memory math: the
 *   [MAX_INPUT_BYTES] gate on the actual file bytes runs BEFORE any decode, and the
 *   bounds-probe dimensions are re-checked against these limits before the full decode.
 * - No API 34 image, no x86_64 image, no physical device in this environment (same
 *   criterion as HXA-050~054); the physical low-memory device matrix is outstanding.
 *
 * Provider evidence (fetched 2026-09-03; the STRICTEST documented bound wins):
 * - OpenAI (Chat Completions + Responses, images-vision guide): 512 MB total request
 *   payload, 1500 images per request, PNG/JPEG/WEBP/non-animated GIF; effective model
 *   sizing downscales beyond a 2048 px edge (high tier) / 2576 px (Anthropic high-res
 *   tier) — sending far larger images wastes bytes the provider then discards.
 * - Anthropic (Messages vision guide): 10 MB base64 per image (5 MB on Bedrock/GCP),
 *   8000x8000 px hard dimension cap, 100 images per request (200k-context models),
 *   32 MB request size on the standard endpoint.
 * - The per-image base64 budget therefore takes the strictest per-image bound (the
 *   OpenAI-documented 4 MB data URL), the dimension cap takes the strictest hard cap
 *   (Anthropic 8000, with the product edge well below it), and the per-request total
 *   takes the strictest request bound (Anthropic 32 MB).
 */
public object VisionLimits {
    /**
     * Maximum RAW input bytes of one attached image at staging/import.
     * Identical to `AttachmentClassifier.MAX_ATTACHMENT_BYTES` (10 MiB) so the import
     * cap and the vision cap cannot drift apart; the wire budgets below are the binding
     * constraint for sendable images.
     */
    public const val MAX_INPUT_BYTES: Long = 10L * 1024 * 1024

    /**
     * Maximum edge (px) of the NORMALIZED image. Above both providers' effective
     * resolution tiers (OpenAI high 2048 px, Anthropic high-res 2576 px long edge —
     * larger images are downscaled server-side) yet below Anthropic's 8000 px hard cap.
     */
    public const val MAX_EDGE_PX: Int = 4096

    /**
     * Maximum total pixels of the NORMALIZED image (12 MP). Decode envelope: 12 MP of
     * ARGB_8888 is a one-shot 48 MiB NATIVE allocation — measured decodable with
     * headroom against the 216 MiB low-memory threshold on the API 29 emulator, and
     * the tightest device-side bound available in this environment.
     */
    public const val MAX_TOTAL_PIXELS: Long = 12_288_000L

    /**
     * Maximum base64 bytes of one image on the wire. The strictest documented
     * per-image bound (OpenAI 4 MB data URL) with headroom: 3.62 MiB base64 decodes to
     * ~2.72 MiB raw, so the [MAX_NORMALIZED_RAW_BYTES] invariant always stays under it.
     */
    public const val MAX_BASE64_PER_IMAGE_BYTES: Int = 3_800_000

    /**
     * Maximum RAW bytes of the NORMALIZED (re-encoded, EXIF-stripped) artifact.
     * `MAX_BASE64_PER_IMAGE_BYTES * 3 / 4` — the base64 of a raw file this size is
     * strictly under the per-image wire budget.
     */
    public const val MAX_NORMALIZED_RAW_BYTES: Int = 2_850_000

    /**
     * Maximum TOTAL base64 bytes of images in one model request
     * (`ModelMessage.MAX_IMAGES_PER_MESSAGE` × [MAX_BASE64_PER_IMAGE_BYTES]).
     * Under the strictest request-size bound (Anthropic 32 MB standard endpoint) with
     * ~17 MiB left for text, tool schemas and framing.
     */
    public const val MAX_TOTAL_BASE64_PER_REQUEST_BYTES: Int = 4 * MAX_BASE64_PER_IMAGE_BYTES

    /**
     * The closed media types an image attachment may normalize to. Matches
     * `ImageReference.MEDIA_TYPES`; animated input (GIF) is normalized to its first
     * frame as JPEG (providers use only the first frame anyway — Anthropic docs).
     */
    public val NORMALIZED_MEDIA_TYPES: Set<String> =
        setOf("image/jpeg", "image/png", "image/webp", "image/gif")

    /**
     * The decode/normalization budget for ONE input image, expressed as the decode
     * envelope the normalizer must enforce before and during the full decode.
     */
    public fun normalizedEdgeFits(
        width: Int,
        height: Int,
    ): Boolean =
        width > 0 && height > 0 &&
            width <= MAX_EDGE_PX && height <= MAX_EDGE_PX &&
            width.toLong() * height <= MAX_TOTAL_PIXELS

    /**
     * The smallest power-of-two `inSampleSize` such that the sampled dimensions fit the
     * normalized envelope (0 when the input already fits; Int.MAX_VALUE when even the
     * largest practical sample cannot fit — the caller then fails closed).
     */
    @Suppress("ReturnCount") // already-fits / first fitting sample / the fail-closed sentinel
    public fun requiredInSampleSize(width: Int, height: Int): Int {
        if (normalizedEdgeFits(width, height)) return 1
        var sample = 1
        while (sample < 128) {
            val w = width / sample
            val h = height / sample
            if (w > 0 && h > 0 && normalizedEdgeFits(w, h)) return sample
            sample *= 2
        }
        return Int.MAX_VALUE
    }
}
