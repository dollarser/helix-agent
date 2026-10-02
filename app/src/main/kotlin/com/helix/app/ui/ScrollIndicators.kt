@file:Suppress("MatchingDeclarationName") // Shared scroll presentation for forms and lazy management lists.

package com.helix.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Adds a persistent overflow cue without consuming touch, focus, or scroll semantics. */
@Composable
internal fun Modifier.indicatedVerticalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    reverseScrolling: Boolean = false,
): Modifier =
    formScrollIndicator(state, MaterialTheme.colorScheme.primary, reverseScrolling)
        .padding(end = 8.dp)
        .verticalScroll(state, enabled = enabled, reverseScrolling = reverseScrolling)

/** Keeps the caller's exact list state, item keys, and scrolling behavior. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun IndicatedLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.lazyScrollIndicator(state, MaterialTheme.colorScheme.primary).padding(end = 8.dp),
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

private fun Modifier.lazyScrollIndicator(
    state: LazyListState,
    color: Color,
): Modifier =
    drawWithContent {
        drawContent()
        val layout = state.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull()
        val last = layout.visibleItemsInfo.lastOrNull()
        val hasOverflow = state.canScrollForward || state.canScrollBackward
        if (hasOverflow && first != null && last != null) {
            val leading =
                first.index +
                    (
                        (layout.viewportStartOffset - first.offset).toFloat() /
                            first.size.coerceAtLeast(
                                1,
                            )
                    ).coerceIn(0f, 1f)
            val trailing =
                last.index +
                    ((layout.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
            val thumb = lazyScrollThumb(size.height, layout.totalItemsCount, leading, trailing, 20.dp.toPx())
            val width = 3.dp.toPx()
            val left = if (layoutDirection == LayoutDirection.Rtl) 0f else (size.width - width).coerceAtLeast(0f)
            val top = if (layout.reverseLayout) size.height - thumb.first - thumb.second else thumb.first
            drawRoundRect(color.copy(alpha = 0.15f), Offset(left, 0f), Size(width, size.height), CornerRadius(width))
            drawRoundRect(color, Offset(left, top), Size(width, thumb.second), CornerRadius(width))
        }
    }

/** Item-fraction estimate supports partially visible and individually oversized items. */
internal fun lazyScrollThumb(
    viewport: Float,
    total: Int,
    leading: Float,
    trailing: Float,
    minimum: Float,
): Pair<Float, Float> {
    require(viewport.isFinite() && viewport >= 0f && total >= 0 && minimum.isFinite() && minimum >= 0f)
    require(leading.isFinite() && trailing.isFinite())
    if (total == 0 || viewport == 0f) return 0f to viewport
    val visible = (trailing - leading).coerceIn(0f, total.toFloat())
    val height = (viewport * visible / total).coerceIn(minOf(minimum, viewport), viewport)
    val remaining = total - visible
    val progress = if (remaining > 0f) (leading / remaining).coerceIn(0f, 1f) else 0f
    return (viewport - height) * progress to height
}
