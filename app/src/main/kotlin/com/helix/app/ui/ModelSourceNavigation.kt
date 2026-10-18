package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ui.providerGroupLabel
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
    val sources = orderedSources(groups)
    if (sources.isEmpty()) return
    PrimaryScrollableTabRow(selectedTabIndex = sources.indexOf(selected).coerceAtLeast(0), edgePadding = 0.dp) {
        sources.forEach { source ->
            Tab(
                selected = source == selected,
                onClick = { onSelect(source) },
                text = { Text(stringResource(providerGroupLabel(source)), maxLines = 1) },
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        orderedSources(groups).forEach { source ->
            OutlinedButton(
                onClick = { onConfigure(source) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-model-configure-${source.name}"),
            ) { Text(stringResource(providerGroupLabel(source))) }
        }
    }
}

internal fun modelSourceDescription(source: ProviderProvisioningKind): Int =
    when (source) {
        ProviderProvisioningKind.BUNDLED_SERVICE -> R.string.builtin_model_description
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.model_source_api_description
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.model_source_account_description
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.model_source_local_description
    }

internal fun modelSourceEmpty(source: ProviderProvisioningKind): Int =
    when (source) {
        ProviderProvisioningKind.BUNDLED_SERVICE -> R.string.builtin_model_unverified
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.provider_empty
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.model_source_account_empty
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.model_source_local_empty
    }
