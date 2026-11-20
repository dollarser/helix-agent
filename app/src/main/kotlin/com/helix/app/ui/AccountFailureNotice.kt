package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.R
import com.helix.provider.api.wire.HttpFailureDetails

/** Only parsed, bounded diagnostics are visible; exceptions and raw server bodies never are. */
@Composable
@Suppress("FunctionName")
internal fun AccountFailureNotice(
    label: Int,
    details: HttpFailureDetails?,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(details?.requestId) { mutableStateOf(false) }
    Column {
        Text(
            details?.detail?.takeIf { it.isNotBlank() } ?: stringResource(label),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("account-failure"),
        )
        details?.retryAfterMillis?.takeIf { it > 0 }?.let { delay ->
            val seconds = delay / 1000 + if (delay % 1000 > 0) 1 else 0
            Text(stringResource(R.string.account_retry_after, seconds), style = MaterialTheme.typography.bodySmall)
        }
        details?.requestId?.let { requestId ->
            Text(stringResource(R.string.account_request_id, requestId), style = MaterialTheme.typography.bodySmall)
            TextButton({
                clipboard.setText(AnnotatedString(requestId))
                copied = true
            }, Modifier.testTag("account-copy-request-id")) {
                val copyLabel = if (copied) R.string.account_request_id_copied else R.string.account_copy_request_id
                Text(stringResource(copyLabel))
            }
        }
    }
}
