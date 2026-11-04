package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
    readFrame: suspend (DisplayExecutionUi) -> Bitmap? = {
        withContext(Dispatchers.IO) { AutomationModule.backgroundPreview(it) }
    },
) {
    if (display?.state != DisplayExecutionState.ACTIVE) return
    var hidden by rememberSaveable(display.sessionId, display.turnId, display.generation) { mutableStateOf(false) }
    var expanded by rememberSaveable(display.sessionId, display.turnId, display.generation) { mutableStateOf(false) }
    var xFraction by rememberSaveable { mutableStateOf(1f) }
    var yFraction by rememberSaveable { mutableStateOf(0.75f) }
    var frame by remember(display.sessionId, display.turnId, display.generation) { mutableStateOf<Bitmap?>(null) }
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
                        frame = readFrame(display)
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
        val requestedWidth = previewWidth(hidden, expanded)
        val ratio = frame?.let { it.width.toFloat() / it.height } ?: (240f / 426f)
        val width =
            if (hidden) {
                minOf(requestedWidth, maxWidth)
            } else {
                minOf(requestedWidth, maxWidth, (maxHeight - 32.dp).coerceAtLeast(48.dp) * ratio)
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
                    Row(
                        Modifier
                            .pointerInput(availableX, availableY) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    if (availableX > 0) xFraction = (xFraction + drag.x / availableX).coerceIn(0f, 1f)
                                    if (availableY > 0) yFraction = (yFraction + drag.y / availableY).coerceIn(0f, 1f)
                                }
                            }.testTag("background-preview-drag"),
                    ) {
                        Text(
                            title,
                            Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                            IconButton(
                                onClick = { hidden = true },
                                modifier = Modifier.size(32.dp).testTag("background-preview-hide"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    stringResource(R.string.background_preview_hide),
                                    Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    val bitmap = frame
                    val imageDescription =
                        if (expanded) {
                            R.string.background_preview_collapse
                        } else {
                            R.string.background_preview_expand
                        }
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            stringResource(imageDescription),
                            Modifier
                                .width(width)
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                                .clickable { expanded = !expanded }
                                .testTag("background-preview-image"),
                        )
                    } else {
                        Text(
                            stringResource(R.string.background_preview_waiting),
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

private fun previewWidth(
    hidden: Boolean,
    expanded: Boolean,
) = when {
    hidden -> 104.dp
    expanded -> 260.dp
    else -> 112.dp
}

private fun previewTitle(
    name: String?,
    fallback: String,
) = name?.takeIf(String::isNotBlank) ?: fallback
