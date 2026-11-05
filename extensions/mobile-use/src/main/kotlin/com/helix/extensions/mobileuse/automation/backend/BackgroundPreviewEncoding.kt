package com.helix.extensions.mobileuse.automation.backend

internal val BACKGROUND_PREVIEW_WIDTHS = listOf(720, 480, 240)

/** Bounded JPEG attempts keep noisy frames below the same Binder payload ceiling. */
internal fun encodeBackgroundPreview(
    width: Int,
    encode: (Int, Int) -> ByteArray,
): ByteArray? {
    require(width in BACKGROUND_PREVIEW_WIDTHS)
    for (target in BACKGROUND_PREVIEW_WIDTHS.filter { it <= width }) {
        for (quality in listOf(if (target == 240) 75 else 82, 55)) {
            val bytes = encode(target, quality)
            if (bytes.isNotEmpty() && bytes.size <= BackgroundPreviewTransport.MAX_BYTES) return bytes
        }
    }
    return null
}
