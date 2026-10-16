package com.helix.app.ui

import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer

/** Preview-only sampling off the main thread; it never changes the saved artifact. */
@Composable
internal fun rememberArtifactBitmap(imageBytes: ByteArray): State<ImageBitmap?> =
    // A changed artifact gets a new state immediately, not the previous image while decoding.
    key(imageBytes) {
        produceState<ImageBitmap?>(null, imageBytes) {
            value =
                withContext(Dispatchers.Default) {
                    decodePreviewBitmap(imageBytes, 1024)
                }
        }
    }

/** ImageDecoder honors camera orientation while bounding the decoded allocation, including draft images. */
@Suppress("SwallowedException") // Malformed/unreadable images show the unavailable state.
internal fun decodePreviewBitmap(bytes: ByteArray, maxEdge: Int): ImageBitmap? =
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
