package com.helix.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Draw a first frame without reading any service or storage-backed preferences. */
@Composable
@Suppress("FunctionName")
internal fun AppStartupScreen(
    state: AppStartupState,
    onConfirmReset: (com.helix.core.storage.PreviewDatabaseResetRequired) -> Unit,
    onExit: () -> Unit,
) {
    HelixTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .testTag("app-startup"),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    is AppStartupState.ResetRequired -> {
                        Text(stringResource(R.string.preview_reset_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.preview_reset_message))
                        Button(
                            onClick = { onConfirmReset(state.request) },
                            modifier = Modifier.testTag("preview-reset-confirm"),
                        ) {
                            Text(stringResource(R.string.preview_reset_confirm))
                        }
                        TextButton(onClick = onExit, modifier = Modifier.testTag("preview-reset-cancel")) {
                            Text(stringResource(R.string.preview_reset_cancel))
                        }
                    }

                    AppStartupState.Failed -> {
                        Text(stringResource(R.string.app_start_failed))
                        TextButton(onClick = onExit) { Text(stringResource(R.string.preview_start_exit)) }
                    }

                    AppStartupState.Loading, AppStartupState.Ready -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.app_starting))
                    }
                }
            }
        }
    }
}
