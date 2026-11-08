package com.helix.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress

internal fun boundedPreviewWidth(
    requested: Float,
    maximum: Float,
    minimum: Float = 96f,
): Float {
    val limit = maximum.coerceAtLeast(1f)
    return requested.coerceIn(minOf(minimum, limit), limit)
}

/** Accessibility adjustment remains available on the image, without an overlay control. */
internal fun Modifier.previewResizeSemantics(
    width: Float,
    maximum: Float,
    minimum: Float,
    resize: (Float) -> Unit,
): Modifier =
    semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(width, minOf(minimum, maximum)..maximum)
        setProgress {
            resize(boundedPreviewWidth(it, maximum, minimum))
            true
        }
    }
