package com.helix.app.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preview-only sampling off the main thread; it never changes the saved artifact. */
@Composable
internal fun rememberArtifactBitmap(imageBytes: ByteArray): State<ImageBitmap?> =
    // A changed artifact gets a new state immediately, not the previous image while decoding.
    key(imageBytes) {
        produceState<ImageBitmap?>(null, imageBytes) {
            value =
                withContext(Dispatchers.Default) {
                    imageBytes.takeIf { it.isNotEmpty() }?.let { bytes ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                        while (bounds.outWidth / options.inSampleSize > 1024 ||
                            bounds.outHeight / options.inSampleSize > 1024
                        ) {
                            options.inSampleSize *= 2
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                    }
                }
        }
    }
