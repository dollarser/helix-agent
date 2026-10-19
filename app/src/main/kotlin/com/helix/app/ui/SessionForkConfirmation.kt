package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.MessageUi

/** Confirmation contains only a preview; the conversation service owns history validation. */
@Composable
@Suppress("FunctionName")
internal fun SessionForkConfirmation(
    message: MessageUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("session-fork-confirmation"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_fork_confirm_title)) },
        text = {
            Column {
                Text(stringResource(R.string.session_fork_confirm_scope))
                Text(
                    message.content.take(240).let { if (message.content.length > 240) "$it…" else it },
                    Modifier.testTag("session-fork-source"),
                )
            }
        },
        confirmButton = {
            TextButton(onConfirm, Modifier.testTag("session-fork-confirm")) {
                Text(stringResource(R.string.session_fork_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("session-fork-cancel")) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
