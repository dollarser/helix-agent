package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ui.SubscriptionSetupHint
import com.helix.feature.conversation.R as ConversationR

/** First-use shortcut for an unconfigured source; it never selects or bypasses provider testing. */
@Composable
@Suppress("FunctionName")
internal fun ModelSetupRow(
    row: ProviderRowUi,
    onConfigure: (() -> Unit)?,
) {
    Column(Modifier.padding(vertical = 8.dp).testTag("chat-model-setup-${row.id}")) {
        Text(
            stringResource(ConversationR.string.model_picker_needs_setup, row.displayName),
            style = MaterialTheme.typography.titleSmall,
        )
        SubscriptionSetupHint(row)
        if (onConfigure != null) {
            TextButton(onConfigure, Modifier.testTag("chat-model-setup-open-${row.id}")) {
                Text(stringResource(ConversationR.string.model_picker_setup_action))
            }
        }
    }
}
