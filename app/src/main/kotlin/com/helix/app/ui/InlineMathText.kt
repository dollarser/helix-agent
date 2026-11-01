package com.helix.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Native text/links and bitmap spans; all visible formulae share the existing single transient engine. */
@Composable
@Suppress("FunctionName", "LongMethod") // One paragraph preserves annotated links and original fallback text.
internal fun InlineMathText(
    source: String,
    style: TextStyle,
    color: Color,
    fontStyle: FontStyle?,
    searchQuery: String?,
    highlight: SpanStyle,
) {
    val math = remember(source) { inlineMath(source) }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scale = LocalDensity.current.fontScale
    val window = LocalWindowInfo.current.containerSize
    val resumed = rememberPreviewResumed()
    var width by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    var preview by remember(source) { mutableStateOf<String?>(null) }
    val inputs =
        remember(math, colors.surface, colors.onSurface, color, style, width, density.density, scale) {
            math.map { part ->
                InlineDiagramInput(
                    part.source,
                    BlockPreviewKind.INLINE_FORMULA,
                    android.graphics.Color.TRANSPARENT,
                    (if (color == Color.Unspecified) colors.onSurface else color).toArgb(),
                    (style.fontSize.value.takeIf { it.isFinite() && it > 0 } ?: 16f) / 16f * scale,
                    width,
                    density.density,
                )
            }
        }
    val states = remember(inputs) { inputs.map { InlineDiagramState(32f) } }
    val citation = LocalCitationHandler.current
    val content =
        buildAnnotatedString {
            var offset = 0
            math.forEachIndexed { index, part ->
                append(markdownInline(source.substring(offset, part.start), citation))
                val original = source.substring(part.start, part.end)
                if (states[index].pixelWidth > 0) {
                    appendInlineContent("math-$index", original)
                } else {
                    append(original)
                }
                offset = part.end
            }
            append(markdownInline(source.substring(offset), citation))
        }
    val spans = mathSpans(states, math, density) { preview = it }
    Box(
        Modifier.fillMaxWidth().testTag("inline-math-paragraph").onGloballyPositioned {
            width = it.size.width
            visible = it.boundsInWindow().overlaps(Rect(0f, 0f, window.width.toFloat(), window.height.toFloat()))
        },
    ) {
        val shown =
            if (searchQuery.isNullOrBlank()) {
                content
            } else {
                highlightAnnotatedString(
                    content,
                    searchQuery,
                    highlight,
                )
            }
        Text(
            shown,
            style = style,
            color = color,
            fontStyle = fontStyle,
            inlineContent = spans,
        )
        states.forEachIndexed { index, state ->
            val enabled = visible && resumed && width > 0
            LaunchedEffect(inputs[index], enabled) { state.load(inputs[index], enabled) }
            state.render?.let { lease ->
                key(lease) {
                    AndroidView(
                        factory = { context ->
                            lease.create(context) { state.height = it.coerceIn(1f, 360f) }.apply {
                                isEnabled = false
                                isFocusable = false
                            }
                        },
                        onRelease = lease::release,
                        modifier =
                            Modifier
                                .layout { measurable, _ ->
                                    val pixels = with(density) { state.height.dp.roundToPx() }.coerceAtLeast(1)
                                    val placeable =
                                        measurable.measure(
                                            Constraints.fixed(width.coerceAtLeast(1), pixels),
                                        )
                                    layout(0, 0) { placeable.place(0, 0) }
                                }.alpha(0f)
                                .clearAndSetSemantics {},
                    )
                }
            }
        }
    }
    preview?.let { BlockPreviewDialog(it, BlockPreviewKind.FORMULA) { preview = null } }
}

private fun mathSpans(
    states: List<InlineDiagramState>,
    math: List<InlineMath>,
    density: androidx.compose.ui.unit.Density,
    onPreview: (String) -> Unit,
): Map<String, InlineTextContent> =
    states
        .mapIndexedNotNull { index, state ->
            if (state.pixelWidth <= 0) {
                null
            } else {
                run {
                    val w = with(density) { state.pixelWidth.toDp().toSp() }
                    val h = with(density) { state.pixelHeight.toDp().toSp() }
                    "math-$index" to
                        InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                            val expression = math[index].source
                            state.bitmap?.let { image ->
                                Image(
                                    image.asImageBitmap(),
                                    expression,
                                    Modifier.testTag("inline-math-image").clickable {
                                        onPreview(expression)
                                    },
                                )
                            }
                        }
                }
            }
        }.toMap()
