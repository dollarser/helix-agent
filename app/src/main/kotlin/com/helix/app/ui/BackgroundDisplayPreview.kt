package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.helix.app.R
import com.helix.app.automation.AutomationModule
import com.helix.app.chat.DisplayExecutionState
import com.helix.app.chat.DisplayExecutionUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A local read-only view. Touches never enter Mobile Use's action or observation pipeline. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun BackgroundDisplayPreview(
    display: DisplayExecutionUi?,
    readName: suspend (DisplayExecutionUi) -> String? = {
        withContext(Dispatchers.IO) { AutomationModule.backgroundApplicationName(it) }
    },
    readFrame: suspend (DisplayExecutionUi, Int) -> Bitmap? = { value, width ->
        withContext(Dispatchers.IO) { AutomationModule.backgroundPreview(value, width) }
    },
) {
    if (display?.state != DisplayExecutionState.ACTIVE) return
    var hidden by rememberSaveable(display.sessionId, display.turnId, display.generation) { mutableStateOf(false) }
    var requestedWidthDp by rememberSaveable { mutableStateOf(112f) }
    var xFraction by rememberSaveable { mutableStateOf(1f) }
    var yFraction by rememberSaveable { mutableStateOf(0.75f) }
    var frame by remember(display.sessionId, display.turnId, display.generation) { mutableStateOf<Bitmap?>(null) }
    var targetWidth by remember { mutableStateOf(240) }
    var frameRatio by remember(display.sessionId, display.turnId, display.generation) { mutableStateOf(240f / 426f) }
    var appName by remember(display.lastObservedPackage, display.generation) { mutableStateOf<String?>(null) }
    LaunchedEffect(display.lastObservedPackage, display.generation) { appName = readName(display) }
    val title = previewTitle(appName, stringResource(R.string.background_preview_title))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(display.sessionId, display.turnId, display.generation, hidden, lifecycle) {
        frame = null
        if (!hidden) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    while (true) {
                        val next = readFrame(display, targetWidth)
                        if (next != null) frameRatio = next.width.toFloat() / next.height
                        frame = next
                        delay(250)
                    }
                } finally {
                    frame = null
                }
            }
        }
    }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize().imePadding().padding(top = 72.dp, bottom = 160.dp, start = 8.dp, end = 8.dp),
    ) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val requestedWidth = if (hidden) 104.dp else requestedWidthDp.dp
        val ratio = frameRatio
        val width =
            if (hidden) {
                minOf(requestedWidth, maxWidth)
            } else {
                minOf(requestedWidth, maxWidth, (maxHeight - 28.dp).coerceAtLeast(48.dp) * ratio)
            }
        SideEffect { targetWidth = previewFrameWidth(with(density) { width.toPx() }, targetWidth) }
        val maximumWidth = minOf(maxWidth, (maxHeight - 28.dp).coerceAtLeast(1.dp) * ratio).value
        val resize: (Float) -> Unit = { requestedWidthDp = boundedPreviewWidth(it, maximumWidth) }
        val transform =
            rememberTransformableState { _, zoom, _, _ ->
                resize(boundedPreviewWidth(requestedWidthDp, maximumWidth) * zoom)
            }
        val availableX = with(density) { maxWidth.toPx() } - size.width
        val availableY = with(density) { maxHeight.toPx() } - size.height
        Surface(
            modifier =
                Modifier
                    .width(width)
                    .offset {
                        IntOffset(
                            (availableX.coerceAtLeast(0f) * xFraction).roundToInt(),
                            (availableY.coerceAtLeast(0f) * yFraction).roundToInt(),
                        )
                    }.onSizeChanged { size = it }
                    .testTag("background-preview"),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            if (hidden) {
                TextButton(
                    onClick = { hidden = false },
                    modifier = Modifier.testTag("background-preview-reopen"),
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Column {
                    BackgroundPreviewHeader(title, width.value, onDrag = { drag ->
                        if (availableX > 0) xFraction = (xFraction + drag.x / availableX).coerceIn(0f, 1f)
                        if (availableY > 0) yFraction = (yFraction + drag.y / availableY).coerceIn(0f, 1f)
                    }, onHide = { hidden = true })
                    val bitmap = frame
                    val expanded = width > 160.dp
                    Box(
                        Modifier
                            .width(
                                width,
                            ).aspectRatio(ratio)
                            .transformable(transform)
                            .previewResizeSemantics(width.value, maximumWidth, resize)
                            .testTag("background-preview-viewport"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(),
                                stringResource(previewImageDescription(expanded)),
                                Modifier
                                    .fillMaxSize()
                                    .clickable { resize(previewToggleWidth(expanded)) }
                                    .testTag("background-preview-image"),
                            )
                        } else {
                            Text(
                                stringResource(R.string.background_preview_waiting),
                                Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        PreviewResizeControl(
                            width.value,
                            maximumWidth,
                            ratio,
                            Modifier.align(Alignment.BottomEnd),
                            resize,
                        )
                    }
                }
            }
        }
    }
}

private fun previewTitle(
    name: String?,
    fallback: String,
) = name?.takeIf(String::isNotBlank) ?: fallback

private fun previewToggleWidth(expanded: Boolean) = if (expanded) 112f else 260f

private fun previewImageDescription(expanded: Boolean) =
    if (expanded) R.string.background_preview_collapse else R.string.background_preview_expand
