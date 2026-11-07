package com.helix.ui.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Native image by default. Only visible, resumed, uncached blocks may borrow the one transient engine. */
@Composable
@Suppress("FunctionName", "LongMethod")
fun InlineDiagramBlock(
    source: String,
    kind: BlockPreviewKind,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalConfiguration.current.fontScale
    val density = LocalDensity.current.density
    val window = LocalWindowInfo.current.containerSize
    val resumed = rememberPreviewResumed()
    var width by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }
    val input =
        remember(source, kind, colors.surface, colors.onSurface, font, width, density) {
            InlineDiagramInput(source, kind, colors.surface.toArgb(), colors.onSurface.toArgb(), font, width, density)
        }
    val state =
        remember(input.key) {
            val height =
                InlineDiagramCache.get(input.key)?.let {
                    it.height.toFloat() / it.width * width / density
                } ?: 96f
            InlineDiagramState(height)
        }
    var showSource by remember(source, kind) { mutableStateOf(false) }
    var zoom by remember(source, kind) { mutableStateOf(false) }
    val enabled = visible && resumed && !showSource && !zoom && width > 0
    LaunchedEffect(input.key, enabled, state.attempt) { state.load(input, enabled) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .onGloballyPositioned { coordinates ->
                width = coordinates.size.width
                val bounds = coordinates.boundsInWindow()
                visible = bounds.overlaps(Rect(0f, 0f, window.width.toFloat(), window.height.toFloat()))
            }.testTag("inline-diagram"),
    ) {
        InlineDiagramHeader(source, kind, showSource, state.failed) {
            if (state.failed && !showSource) state.retry() else showSource = !showSource
        }
        InlineDiagramVisual(source, kind, state, showSource) { zoom = true }
    }
    if (zoom) BlockPreviewDialog(source, kind) { zoom = false }
}

@Composable
@Suppress("FunctionName")
private fun InlineDiagramHeader(
    source: String,
    kind: BlockPreviewKind,
    showSource: Boolean,
    failed: Boolean,
    onToggle: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(source) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(COPY_FEEDBACK_DURATION_MS)
            copied = false
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            if (kind == BlockPreviewKind.SVG) "SVG" else "Mermaid",
            Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(source))
                copied = true
            },
            modifier = Modifier.testTag("inline-diagram-copy"),
        ) {
            Icon(
                painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_chat_copy),
                stringResource(if (copied) R.string.code_copied else R.string.formula_copy),
                Modifier.size(18.dp),
            )
        }
        TextButton(onClick = onToggle, modifier = Modifier.testTag("inline-diagram-toggle")) {
            Text(
                stringResource(
                    when {
                        failed && !showSource -> R.string.diagram_retry
                        showSource -> R.string.diagram_preview
                        else -> R.string.diagram_source
                    },
                ),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun InlineDiagramVisual(
    source: String,
    kind: BlockPreviewKind,
    state: InlineDiagramState,
    showSource: Boolean,
    onZoom: () -> Unit,
) {
    when {
        showSource || state.failed -> {
            if (state.failed) {
                Text(
                    stringResource(R.string.diagram_inline_error),
                    Modifier
                        .padding(8.dp)
                        .testTag("inline-diagram-error"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            InlineDiagramSource(source)
        }

        state.bitmap != null -> {
            val image = requireNotNull(state.bitmap)
            Image(
                image.asImageBitmap(),
                stringResource(kind.title),
                Modifier
                    .fillMaxWidth()
                    .height(state.height.coerceIn(64f, 360f).dp)
                    .clickable(onClickLabel = stringResource(R.string.diagram_zoom)) { onZoom() }
                    .testTag("inline-diagram-image"),
                contentScale = ContentScale.Fit,
            )
        }

        state.render != null -> {
            val lease = requireNotNull(state.render)
            key(lease) {
                AndroidView(
                    factory = { context -> lease.create(context) { state.height = it } },
                    onRelease = { lease.release(it) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(state.height.coerceIn(64f, 360f).dp)
                            .testTag("inline-diagram-renderer"),
                )
            }
        }

        else -> {
            Text(
                stringResource(R.string.formula_loading),
                Modifier
                    .fillMaxWidth()
                    .height(state.height.coerceIn(64f, 360f).dp)
                    .padding(12.dp)
                    .testTag("inline-diagram-loading"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun InlineDiagramSource(source: String) {
    val lines = remember(source) { source.lines() }
    var expanded by remember(source) { mutableStateOf(false) }
    MarkdownCodeContent(if (expanded) lines else lines.take(CODE_BLOCK_COLLAPSE_THRESHOLD), null)
    if (lines.size > CODE_BLOCK_COLLAPSE_THRESHOLD) {
        MarkdownCodeExpandToggle(lines.size, expanded) { expanded = !expanded }
    }
}
