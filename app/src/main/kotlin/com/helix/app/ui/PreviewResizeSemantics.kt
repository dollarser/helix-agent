package com.helix.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress

internal fun boundedPreviewWidth(
    requested: Float,
    maximum: Float,
): Float {
    val limit = maximum.coerceAtLeast(1f)
    return requested.coerceIn(minOf(96f, limit), limit)
}

/** Accessibility adjustment remains available on the image, without an overlay control. */
internal fun Modifier.previewResizeSemantics(
    width: Float,
    maximum: Float,
    resize: (Float) -> Unit,
): Modifier =
    semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(width, minOf(96f, maximum)..maximum)
        setProgress {
            resize(boundedPreviewWidth(it, maximum))
            true
        }
    }
