package com.helix.extensions.mobileuse.automation.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Validate dimensions before allocating decoded pixels, including any lower-resolution fallback. */
internal fun decodeBackgroundPreview(
    bytes: ByteArray,
    requestedWidth: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in BACKGROUND_PREVIEW_WIDTHS || bounds.outWidth > requestedWidth ||
        bounds.outHeight != 1280 * bounds.outWidth / 720
    ) {
        return null
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
