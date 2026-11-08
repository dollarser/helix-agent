package com.helix.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.ui.shared.R as SharedR

/** Transient chrome overlays the frame; its close target stays accessible at every window size. */
@Composable
@Suppress("FunctionName")
internal fun BackgroundPreviewHeader(
    title: String,
    modifier: Modifier,
    onHide: () -> Unit,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("background-preview-controls"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                Modifier.weight(1f).padding(start = 8.dp).testTag("background-preview-title"),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onHide, Modifier.size(48.dp).testTag("background-preview-hide")) {
                Icon(
                    painterResource(SharedR.drawable.ic_close),
                    stringResource(R.string.background_preview_hide),
                    Modifier.size(20.dp),
                )
            }
        }
    }
}
