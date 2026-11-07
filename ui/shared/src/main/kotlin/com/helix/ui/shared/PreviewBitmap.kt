package com.helix.ui.shared

import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.nio.ByteBuffer

/** ImageDecoder honors camera orientation while bounding the decoded allocation, including draft images. */
@Suppress("SwallowedException") // Malformed/unreadable images show the unavailable state.
fun decodePreviewBitmap(
    bytes: ByteArray,
    maxEdge: Int,
): ImageBitmap? =
    try {
        if (bytes.isEmpty()) {
            null
        } else {
            ImageDecoder
                .decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1),
                    )
                }.asImageBitmap()
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
