package com.helix.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.helix.app.files.FileManagerService.FileMeta

/** One observable result; text/image/metadata cannot describe different loading phases. */
internal sealed interface FilePreviewState {
    data object Loading : FilePreviewState

    data class Ready(
        val text: String?,
        val image: ImageBitmap?,
        val info: FileMeta,
    ) : FilePreviewState

    data class Failed(
        val message: String,
    ) : FilePreviewState
}
