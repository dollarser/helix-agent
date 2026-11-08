package com.helix.app.ui

/** Available dimensions already exclude system bars, the keyboard and the window's safe margin. */
internal fun previewMaximumWidth(
    availableWidth: Float,
    availableHeight: Float,
    ratio: Float,
): Float = minOf(availableWidth, availableHeight * ratio).coerceAtLeast(1f)

/** Keep the close control fully reachable while preserving the frame aspect ratio. */
internal fun previewMinimumWidth(ratio: Float): Float = maxOf(96f, 48f * ratio)

/** The corner must not cover the close target in a short landscape window. */
internal fun previewShowsResizeHandle(
    width: Float,
    ratio: Float,
): Boolean = width / ratio >= 72f

/** Double-tap toggles the actual bounded size, never an unreachable fixed expanded width. */
internal fun previewToggleWidth(
    width: Float,
    maximum: Float,
    minimum: Float = 96f,
): Float {
    val compact = boundedPreviewWidth(112f, maximum, minimum)
    return if (width > compact + 1f) compact else maximum
}
