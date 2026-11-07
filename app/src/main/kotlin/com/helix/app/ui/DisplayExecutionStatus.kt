package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.feature.conversation.presentation.DisplayExecutionState
import com.helix.feature.conversation.presentation.DisplayExecutionUi

internal fun displayExecutionLabel(state: DisplayExecutionState): Int =
    when (state) {
        DisplayExecutionState.CREATING -> R.string.display_live_creating
        DisplayExecutionState.ACTIVE -> R.string.display_live_active
        DisplayExecutionState.RELEASED -> R.string.display_live_released
        DisplayExecutionState.CONNECTION_LOST -> R.string.display_live_disconnected
        DisplayExecutionState.RELEASE_UNKNOWN -> R.string.display_live_release_unknown
        DisplayExecutionState.UNAVAILABLE -> R.string.display_live_unavailable
        DisplayExecutionState.UNKNOWN -> R.string.display_live_unknown
    }

@Composable
@Suppress("FunctionName")
internal fun DisplayExecutionStatus(value: DisplayExecutionUi?) {
    if (value == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(
            Modifier
                .padding(8.dp)
                .testTag("display-live-${value.turnId}")
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                stringResource(displayExecutionLabel(value.state)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            value.lastObservedPackage?.let { target ->
                Text(
                    stringResource(R.string.display_live_target, target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
