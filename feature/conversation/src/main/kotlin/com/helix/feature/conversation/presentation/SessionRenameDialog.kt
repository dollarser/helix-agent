package com.helix.feature.conversation.presentation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.feature.conversation.R
import com.helix.ui.shared.R as SharedR

@Composable
@Suppress("FunctionName")
fun SessionRenameDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename)) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.chat_session_title_label)) },
                modifier = Modifier.testTag("session-title"),
            )
        },
        confirmButton = {
            TextButton(
                { onSave(value.trim()) },
                enabled = value.isNotBlank() && value.length <= 200 && '\u0000' !in value,
                modifier = Modifier.testTag("session-rename-save"),
            ) { Text(stringResource(R.string.chat_save_details)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(SharedR.string.common_cancel)) } },
    )
}
