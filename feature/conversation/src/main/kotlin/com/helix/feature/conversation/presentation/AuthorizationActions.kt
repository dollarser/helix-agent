package com.helix.feature.conversation.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.feature.conversation.R
import com.helix.feature.conversation.R as ConversationR

/** Shared one-time authorization interaction; the owning boundary still validates its exact proof. */
@Composable
@Suppress("FunctionName")
fun AuthorizationActions(
    approveTag: String,
    denyTag: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onApprove, modifier = Modifier.testTag(approveTag)) {
            Text(stringResource(ConversationR.string.approval_action_approve_once))
        }
        OutlinedButton(onClick = onDeny, modifier = Modifier.testTag(denyTag)) {
            Text(stringResource(ConversationR.string.approval_action_deny))
        }
    }
}
