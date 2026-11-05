package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R

/** Retry only re-reads local facts; it never repeats the task which produced them. */
@Composable
@Suppress("FunctionName")
internal fun LocalListReadError(onRetry: () -> Unit) {
    Text(stringResource(R.string.local_list_read_failed))
    TextButton(onRetry) { Text(stringResource(R.string.chat_retry)) }
}
