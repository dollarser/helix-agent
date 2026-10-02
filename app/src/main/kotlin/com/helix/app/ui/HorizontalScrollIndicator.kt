package com.helix.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Bottom overflow cue for chips, breadcrumbs and tables; scrolling remains native Compose behavior. */
@Composable
internal fun Modifier.indicatedHorizontalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    reverseScrolling: Boolean = false,
): Modifier {
    val color = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        if (state.maxValue > 0 && state.maxValue != Int.MAX_VALUE) {
            val height = 3.dp.toPx()
            val thumb = formScrollThumb(size.width, state.maxValue.toFloat(), state.value.toFloat(), 20.dp.toPx())
            val reversed = reverseScrolling != (layoutDirection == LayoutDirection.Rtl)
            val left = if (reversed) size.width - thumb.first - thumb.second else thumb.first
            val top = (size.height - height).coerceAtLeast(0f)
            drawRoundRect(color.copy(alpha = 0.15f), Offset(0f, top), Size(size.width, height), CornerRadius(height))
            drawRoundRect(color, Offset(left, top), Size(thumb.second, height), CornerRadius(height))
        }
    }.padding(bottom = 5.dp).horizontalScroll(state, enabled = enabled, reverseScrolling = reverseScrolling)
}
