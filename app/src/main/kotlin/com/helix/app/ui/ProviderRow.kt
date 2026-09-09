package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderRowUi

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ProviderRow(
    row: ProviderRowUi,
    testing: Boolean,
    actions: ProviderRowActions,
    accountUnavailable: Boolean,
) {
    val visionEnabled = row.capabilities?.vision == true
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("provider-row")
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(row.status)
        }
        Text(
            "${UiLabels.displayOrigin(row.origin)} · ${stringResource(UiLabels.residenceLabelRes(row.residence))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildString {
                append(
                    stringResource(
                        R.string.provider_row_model,
                        row.model,
                        UiLabels.protocolLabel(row.protocol),
                    ),
                )
                if (row.hasKey) append(stringResource(R.string.provider_row_has_key))
                if (row.isCleartext) append(stringResource(R.string.provider_row_cleartext))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val detail = statusDetail(row)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.managedExternally) {
            Text(
                stringResource(R.string.provider_subscription_experimental_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("provider-subscription-notice"),
            )
            if (accountUnavailable) {
                Text(
                    stringResource(R.string.provider_subscription_runtime_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("provider-subscription-runtime-unavailable"),
                )
            }
        }
        // HXA-059: the backend model list, carried out of the LAST PASSED
        // connection test only. A failed/untested row shows no section at all;
        // a passed row without a list gets the explicit manual-entry hint.
        // Selecting a chip PREFILLS the edit form (never auto-saves).
        if (row.status is ConnectionTestStatus.Passed && !row.managedExternally) {
            val models = row.backendModels
            if (models.isNullOrEmpty()) {
                Text(
                    stringResource(R.string.provider_models_unsupported_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("provider-models-unsupported"),
                )
            } else {
                BackendModelsSection(
                    models = models,
                    onModelSelected = { id -> actions.onEdit(id) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.onTest,
                enabled = !testing,
                modifier = Modifier.testTag("provider-test"),
            ) {
                Text(
                    stringResource(
                        if (testing) R.string.provider_testing else R.string.provider_connection_test,
                    ),
                )
            }
            if (!row.managedExternally) {
                TextButton(onClick = { actions.onEdit(null) }, modifier = Modifier.testTag("provider-edit")) {
                    Text(stringResource(R.string.provider_edit_button))
                }
                TextButton(
                    onClick = { actions.onDeclareVision(!visionEnabled) },
                    modifier = Modifier.testTag("provider-vision-declare"),
                ) {
                    Text(
                        stringResource(
                            if (visionEnabled) {
                                R.string.provider_vision_declare_off
                            } else {
                                R.string.provider_vision_declare_on
                            },
                        ),
                    )
                }
                TextButton(
                    onClick = actions.onDelete,
                    modifier = Modifier.testTag("provider-delete"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.provider_delete))
                }
            } else {
                TextButton(
                    onClick = actions.onManageAccount,
                    modifier = Modifier.testTag("provider-manage-account"),
                ) {
                    Text(stringResource(R.string.provider_subscription_manage_account))
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun StatusChip(status: ConnectionTestStatus) {
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
                stringResource(R.string.conn_passed)
            }

            is ConnectionTestStatus.Failed -> {
                stringResource(
                    R.string.conn_failed_phase,
                    stringResource(ConnectionTestMapping.phaseLabel(status.phase)),
                )
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
            if (row.capabilityChips.isEmpty()) {
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
            stringResource(
                R.string.provider_failed_detail,
                stringResource(ConnectionTestMapping.phaseLabel(status.phase)),
                stringResource(ConnectionTestMapping.codeLabel(status.code)),
                if (status.retryable) stringResource(R.string.provider_retryable) else "",
            )
        }
    }
