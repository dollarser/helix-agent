package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.model.ProviderProvisioningKind

private fun orderedSources(groups: List<ProviderProvisioningKind>) =
    listOf(
        ProviderProvisioningKind.USER_CONFIGURED,
        ProviderProvisioningKind.MANAGED_ACCOUNT,
        ProviderProvisioningKind.ON_DEVICE_ASSET,
    ).filter { it in groups }

@Composable
@Suppress("FunctionName")
internal fun ModelSourceTabs(
    groups: List<ProviderProvisioningKind>,
    selected: ProviderProvisioningKind,
    onSelect: (ProviderProvisioningKind) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        orderedSources(groups).forEach { source ->
            FilterChip(
                selected = source == selected,
                onClick = { onSelect(source) },
                label = { Text(stringResource(providerGroupLabel(source))) },
                modifier = Modifier.testTag("provider-group-${source.name}"),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun ModelSourceActions(
    groups: List<ProviderProvisioningKind>,
    onConfigure: (ProviderProvisioningKind) -> Unit,
) {
    Text(stringResource(R.string.model_source_configure), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        orderedSources(groups).forEach { source ->
            OutlinedButton(
                onClick = { onConfigure(source) },
                modifier = Modifier.testTag("chat-model-configure-${source.name}"),
            ) { Text(stringResource(providerGroupLabel(source))) }
        }
    }
}

internal fun modelSourceDescription(source: ProviderProvisioningKind): Int =
    when (source) {
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.model_source_api_description
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.model_source_account_description
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.model_source_local_description
    }

internal fun modelSourceEmpty(source: ProviderProvisioningKind): Int =
    when (source) {
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.provider_empty
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.model_source_account_empty
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.model_source_local_empty
    }
