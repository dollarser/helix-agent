package com.helix.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Visible whenever content overflows, including before the first touch. Does not intercept scrolling. */
internal fun Modifier.formScrollIndicator(
    state: ScrollState,
    color: Color,
    reverseScrolling: Boolean = false,
): Modifier =
    drawWithContent {
        drawContent()
        if (state.maxValue > 0 && state.maxValue != Int.MAX_VALUE) {
            val width = 3.dp.toPx()
            val thumb = formScrollThumb(size.height, state.maxValue.toFloat(), state.value.toFloat(), 20.dp.toPx())
            val left = if (layoutDirection == LayoutDirection.Rtl) 0f else (size.width - width).coerceAtLeast(0f)
            val top = if (reverseScrolling) size.height - thumb.first - thumb.second else thumb.first
            drawRoundRect(color.copy(alpha = 0.15f), Offset(left, 0f), Size(width, size.height), CornerRadius(width))
            drawRoundRect(color, Offset(left, top), Size(width, thumb.second), CornerRadius(width))
        }
    }

/** Returns top and height; clamped for resize/IME transitions and overscroll. */
internal fun formScrollThumb(
    viewport: Float,
    maximum: Float,
    offset: Float,
    minimum: Float,
): Pair<Float, Float> {
    require(viewport.isFinite() && maximum.isFinite() && offset.isFinite() && minimum.isFinite())
    require(viewport >= 0 && maximum >= 0 && minimum >= 0)
    if (maximum == 0f || viewport == 0f) return 0f to viewport
    val height = (viewport * viewport / (viewport + maximum)).coerceIn(minOf(minimum, viewport), viewport)
    return ((viewport - height) * (offset / maximum).coerceIn(0f, 1f)) to height
}
