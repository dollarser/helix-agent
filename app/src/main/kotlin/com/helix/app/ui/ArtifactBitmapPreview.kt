package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import com.helix.ui.shared.decodePreviewBitmap
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
                    decodePreviewBitmap(imageBytes, 1024)
                }
        }
    }
