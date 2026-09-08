package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Detailed controls must leave room for the directory being managed. */
@Composable
@Suppress("FunctionName")
internal fun AdaptiveFileControls(
    location: String,
    content: @Composable () -> Unit,
) {
    val config = LocalConfiguration.current
    var open by remember { mutableStateOf(false) }
    if (config.screenHeightDp > 640 && config.fontScale < 1.3f) {
        content()
    } else {
        Text(
            location,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("files-current-location"),
        )
        TextButton({ open = true }, modifier = Modifier.testTag("files-controls-open")) {
            Text(stringResource(R.string.files_controls))
        }
        if (open) {
            AlertDialog(
                onDismissRequest = { open = false },
                title = { Text(stringResource(R.string.files_controls)) },
                text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { content() } },
                confirmButton = {
                    TextButton({ open = false }, modifier = Modifier.testTag("files-controls-close")) {
                        Text(stringResource(R.string.files_close))
                    }
                },
            )
        }
    }
}
