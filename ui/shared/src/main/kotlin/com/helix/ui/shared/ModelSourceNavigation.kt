package com.helix.ui.shared

import androidx.annotation.StringRes
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
import com.helix.core.model.ProviderProvisioningKind

internal fun orderedSources(groups: List<ProviderProvisioningKind>) =
    listOf(
        ProviderProvisioningKind.USER_CONFIGURED,
        ProviderProvisioningKind.MANAGED_ACCOUNT,
        ProviderProvisioningKind.ON_DEVICE_ASSET,
    ).filter { it in groups }

@Composable
@Suppress("FunctionName")
fun ModelSourceTabs(
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
fun ModelSourceActions(
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

@StringRes
fun providerGroupLabel(group: ProviderProvisioningKind): Int =
    when (group) {
        ProviderProvisioningKind.BUNDLED_SERVICE -> R.string.builtin_model_title
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.local_model_title
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.provider_group_api
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.provider_group_account
    }
