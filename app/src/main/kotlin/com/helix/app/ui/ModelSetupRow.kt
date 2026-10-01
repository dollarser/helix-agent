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

/** A setup route, never a model-selection or test bypass. Sources with no selected models stay discoverable. */
@Composable
@Suppress("FunctionName")
internal fun ModelSetupRow(
    row: ProviderRowUi,
    onManageModels: (() -> Unit)?,
) {
    Column(Modifier.padding(vertical = 8.dp).testTag("chat-model-setup-${row.id}")) {
        Text(
            stringResource(R.string.model_picker_needs_setup, row.displayName),
            style = MaterialTheme.typography.titleSmall,
        )
        SubscriptionSetupHint(row)
        if (onManageModels != null) {
            TextButton(onClick = onManageModels, modifier = Modifier.testTag("chat-model-setup-open-${row.id}")) {
                Text(stringResource(R.string.model_picker_setup_action))
            }
        }
    }
}
