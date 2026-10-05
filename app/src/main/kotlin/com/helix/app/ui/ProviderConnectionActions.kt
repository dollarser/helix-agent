package com.helix.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderSetupStep

@Composable
@Suppress("FunctionName")
internal fun SubscriptionSetupHint(row: ProviderRowUi) {
    val step = ProviderSetupStep.forRow(row)
    if (step == ProviderSetupStep.READY) return
    val label =
        when (step) {
            ProviderSetupStep.ACCOUNT -> R.string.subscription_setup_account
            ProviderSetupStep.CONNECTION -> R.string.subscription_setup_connection
            ProviderSetupStep.MODELS -> R.string.subscription_setup_models
            ProviderSetupStep.READY -> R.string.subscription_setup_ready
        }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("subscription-setup-hint-${row.id}"),
    )
}

@Composable
@Suppress("FunctionName")
internal fun ProviderConnectionTestAction(
    row: ProviderRowUi,
    testing: Boolean,
    detectingCapabilities: Boolean,
    onTest: () -> Unit,
) {
    val highlight = row.managedExternally && !row.chatSelectable
    val label =
        when {
            testing && !detectingCapabilities -> R.string.provider_testing
            highlight -> R.string.subscription_setup_test_action
            row.managedExternally -> R.string.provider_account_connection_test
            else -> R.string.provider_connection_test
        }
    val modifier = Modifier.fillMaxWidth().testTag("provider-test")
    if (highlight) {
        FilledTonalButton(onClick = onTest, enabled = !testing, modifier = modifier) {
            Text(stringResource(label))
        }
    } else {
        OutlinedButton(onClick = onTest, enabled = !testing, modifier = modifier) {
            Text(stringResource(label))
        }
    }
}
