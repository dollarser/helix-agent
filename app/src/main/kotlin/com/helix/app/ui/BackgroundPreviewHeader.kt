package com.helix.app.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.ui.shared.R as SharedR

internal fun previewHeaderHeight(widthDp: Float): Float = (widthDp * 0.14f).coerceIn(20f, 28f)

/** Compact chrome follows window size; text may grow for accessibility without clipping. */
@Composable
@Suppress("FunctionName")
internal fun BackgroundPreviewHeader(
    title: String,
    widthDp: Float,
    onDrag: (Offset) -> Unit,
    onHide: () -> Unit,
) {
    val height = previewHeaderHeight(widthDp).dp
    val drag by rememberUpdatedState(onDrag)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .pointerInput(Unit) {
                detectDragGestures { change, delta ->
                    change.consume()
                    drag(delta)
                }
            }.testTag("background-preview-drag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).padding(start = 6.dp, end = 2.dp).testTag("background-preview-title"),
            style =
                MaterialTheme.typography.labelSmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides height) {
            IconButton(onHide, Modifier.size(height).testTag("background-preview-hide")) {
                Icon(
                    painterResource(SharedR.drawable.ic_close),
                    stringResource(R.string.background_preview_hide),
                    Modifier.size(12.dp),
                )
            }
        }
    }
}
