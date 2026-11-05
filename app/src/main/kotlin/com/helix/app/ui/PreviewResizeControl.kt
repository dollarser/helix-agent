package com.helix.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal fun previewWidthDelta(
    x: Float,
    y: Float,
    ratio: Float,
): Float = (x + y / ratio) / (1f + 1f / (ratio * ratio))

/** Small transparent corner mark; the viewport exposes the accessible size adjustment. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun PreviewResizeControl(
    width: Float,
    maximum: Float,
    ratio: Float,
    modifier: Modifier,
    onResize: (Float) -> Unit,
) {
    val density = LocalDensity.current.density
    val currentWidth by rememberUpdatedState(width)
    val resize by rememberUpdatedState<(Float, Offset) -> Float> { start, drag ->
        boundedPreviewWidth(start + previewWidthDelta(drag.x, drag.y, ratio) / density, maximum).also(onResize)
    }
    Canvas(
        modifier.size(24.dp).testTag("background-preview-resize").pointerInput(Unit) {
            var draggingWidth = currentWidth
            detectDragGestures(onDragStart = { draggingWidth = currentWidth }) { change, drag ->
                change.consume()
                draggingWidth = resize(draggingWidth, drag)
            }
        },
    ) {
        for (step in 1..3) {
            val inset = 3.dp.toPx()
            val length = (step * 3).dp.toPx()
            val start = Offset(size.width - inset - length, size.height - inset)
            val end = Offset(size.width - inset, size.height - inset - length)
            drawLine(Color.Black.copy(alpha = 0.65f), start, end, 3.dp.toPx())
            drawLine(Color.White, start, end, 1.dp.toPx())
        }
    }
}
