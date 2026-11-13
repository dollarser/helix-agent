package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderRowUi
import com.helix.app.ui.managedAccountLabel
import com.helix.core.model.ProviderProvisioningKind
import com.helix.ui.shared.UiLabels
import com.helix.ui.shared.localizedString

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun ProviderRow(
    row: ProviderRowUi,
    testing: Boolean,
    actions: ProviderRowActions,
    accountUnavailable: Boolean,
    detectingCapabilities: Boolean = false,
    connectionOutcome: com.helix.provider.api.ProbeOutcome? = null,
    modelOperationError: String? = null,
) {
    require(!row.bundled) { "Built-in models have no provider configuration surface" }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("provider-row"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.displayName, style = MaterialTheme.typography.titleMedium)
                    if (!row.managedExternally) {
                        Text(
                            providerLocationLabel(row),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!row.managedExternally) ProviderOverflow(row, actions)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusChip(
                    row.status,
                    row.managedExternally,
                    row.provisioning == ProviderProvisioningKind.USER_CONFIGURED,
                )
            }
            row.accountState?.let { account ->
                Text(
                    stringResource(managedAccountLabel(account)),
                    Modifier.testTag("provider-account-state"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.assetSizeBytes?.let { size ->
                Text(stringResource(R.string.local_model_resources, UiLabels.formatBytes(size)))
            }
            if (!row.managedExternally && row.protocol != null) {
                Text(
                    buildString {
                        append(UiLabels.protocolLabel(row.protocol))
                        if (row.hasKey) append(stringResource(R.string.provider_row_has_key))
                        if (row.isCleartext) append(stringResource(R.string.provider_row_cleartext))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (row.isCleartext) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            if (row.status is ConnectionTestStatus.Failed) {
                statusDetail(row)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (accountUnavailable) {
                Text(
                    stringResource(R.string.provider_subscription_runtime_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("provider-subscription-runtime-unavailable"),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        if (row.provisioning == ProviderProvisioningKind.USER_CONFIGURED) {
                            actions.onEdit(null)
                        } else {
                            actions.onManageModels()
                        }
                    },
                    modifier = Modifier.testTag("provider-manage-models-${row.id}"),
                ) {
                    Text(
                        stringResource(
                            if (row.provisioning == ProviderProvisioningKind.USER_CONFIGURED) {
                                R.string.provider_service_settings
                            } else {
                                R.string.provider_models_manage
                            },
                        ),
                    )
                }
                if (row.managedExternally) {
                    OutlinedButton(
                        onClick = actions.onManageAccount,
                        modifier = Modifier.testTag("provider-manage-account"),
                    ) {
                        Text(stringResource(R.string.provider_subscription_manage_account))
                    }
                } else {
                    ProviderConnectionTestAction(row, testing, detectingCapabilities, actions.onTest)
                }
            }
            (connectionOutcome as? com.helix.provider.api.ProbeOutcome.Failed)?.let { failure ->
                if (failure.detail == com.helix.app.provider.ProviderConnectionCheck.CATALOG_UNSUPPORTED) {
                    if ((row.status as? ConnectionTestStatus.Failed)?.catalogUnsupported != true) {
                        Text(
                            stringResource(R.string.provider_directory_unsupported),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.provider_models_operation_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ProviderSelectedModelList(row, testing || detectingCapabilities, actions)
            modelOperationError?.takeIf { it in row.conversationModels }?.let { model ->
                Text(
                    stringResource(R.string.provider_model_check_failed, row.modelLabel(model)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ProviderOverflow(
    row: ProviderRowUi,
    actions: ProviderRowActions,
) {
    var open by remember(row.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("provider-overflow-${row.id}")) {
            Icon(painterResource(R.drawable.ic_provider_more), stringResource(R.string.provider_more_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (row.provisioning == ProviderProvisioningKind.ON_DEVICE_ASSET) {
                DropdownMenuItem(text = { Text(stringResource(R.string.local_model_unload)) }, onClick = {
                    open = false
                    actions.onUnload()
                })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    actions.onDelete()
                },
                modifier = Modifier.testTag("provider-delete"),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun StatusChip(
    status: ConnectionTestStatus,
    accountOnly: Boolean = false,
    apiDirectory: Boolean = false,
) {
    val (color, tag) =
        when (status) {
            ConnectionTestStatus.Untested -> {
                MaterialTheme.colorScheme.surfaceVariant to "provider-status-untested"
            }

            is ConnectionTestStatus.Passed -> {
                MaterialTheme.colorScheme.primaryContainer to "provider-status-passed"
            }

            is ConnectionTestStatus.Failed -> {
                MaterialTheme.colorScheme.errorContainer to "provider-status-failed"
            }
        }
    val label =
        when (status) {
            ConnectionTestStatus.Untested -> {
                stringResource(R.string.conn_untested)
            }

            is ConnectionTestStatus.Passed -> {
                stringResource(
                    if (accountOnly) {
                        R.string.provider_account_connected
                    } else if (apiDirectory && status.modelIds != null) {
                        R.string.provider_directory_passed
                    } else if (apiDirectory) {
                        R.string.provider_model_connection_passed
                    } else if (status.capabilities.source == com.helix.provider.api.CapabilitySource.CONNECTION_ONLY) {
                        R.string.conn_connection_only
                    } else {
                        R.string.conn_passed
                    },
                )
            }

            is ConnectionTestStatus.Failed -> {
                if (status.catalogUnsupported) {
                    stringResource(R.string.provider_directory_unsupported_short)
                } else {
                    stringResource(
                        R.string.conn_failed_phase,
                        stringResource(ConnectionTestMapping.phaseLabel(status.phase)),
                    )
                }
            }
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The status detail line under a provider row (safe labels only). Composable because it resolves
 * the stable resource ids to the current locale (HXA-069); the phase/code labels are SAFE mapper
 * output, never raw exception text (doc 02 section 13).
 */
@Composable
private fun statusDetail(row: ProviderRowUi): String? =
    when (val status = row.status) {
        ConnectionTestStatus.Untested -> {
            stringResource(R.string.provider_untested_detail)
        }

        is ConnectionTestStatus.Passed -> {
            if (row.provisioning == com.helix.core.model.ProviderProvisioningKind.USER_CONFIGURED ||
                row.capabilityChips.isEmpty()
            ) {
                null
            } else {
                // Compose: resolve each capability chip in a `for` loop (composable scope); a
                // `joinToString` transform lambda is not composable and would not compile.
                val chips = mutableListOf<String>()
                for (chip in row.capabilityChips) {
                    chips.add(localizedString(chip.res, chip.args))
                }
                stringResource(
                    R.string.provider_capability_detail,
                    chips.joinToString("  "),
                )
            }
        }

        is ConnectionTestStatus.Failed -> {
            if (status.catalogUnsupported) {
                stringResource(R.string.provider_directory_unsupported)
            } else {
                stringResource(
                    R.string.provider_failed_detail,
                    stringResource(ConnectionTestMapping.phaseLabel(status.phase)),
                    stringResource(
                        if (row.managedExternally && status.code == com.helix.core.model.ModelErrorCode.TRANSPORT) {
                            R.string.conn_subscription_transport
                        } else {
                            ConnectionTestMapping.codeLabel(status.code)
                        },
                    ),
                    if (status.retryable) stringResource(R.string.provider_retryable) else "",
                )
            }
        }
    }

@Composable
private fun providerLocationLabel(row: ProviderRowUi): String {
    val residence = stringResource(UiLabels.residenceLabelRes(row.residence))
    return if (row.protocol == null) residence else "${UiLabels.displayOrigin(row.origin)} · $residence"
}
