package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ManagedProviderAccountResult
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.app.provider.sameProviderEditableConfig
import com.helix.app.ui.managedAccountLabel
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.helix.ui.shared.R as SharedR

/** Account checks use the same service and durable evidence as the source list. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ProviderAccountDialog(
    row: ProviderRowUi,
    service: ProviderService,
    onDismiss: () -> Unit,
) {
    require(row.managedExternally)
    val rows by service.rows.collectAsStateWithLifecycle()
    val live = rows.firstOrNull { it.id == row.id }
    val current = live ?: row
    var busy by remember(row.id) { mutableStateOf(false) }
    var opening by remember(row.id) { mutableStateOf(false) }
    var unavailable by remember(row.id) { mutableStateOf(false) }
    var failed by remember(row.id) { mutableStateOf(false) }
    var resultTarget by remember(row.id) { mutableStateOf<ProviderRowUi?>(null) }
    val scope = rememberCoroutineScope()

    fun perform(openLogin: Boolean) {
        if (busy || live == null) return
        busy = true
        resultTarget = current
        opening = openLogin
        unavailable = false
        failed = false
        scope.launch {
            try {
                if (openLogin) {
                    unavailable = service.openManagedAccount(row.id) != ManagedProviderAccountResult.OPENED
                } else {
                    failed =
                        service.runConnectionTest(
                            row.id,
                            current.model,
                            expectedConfig = current.configuration,
                        ) is ProbeOutcome.Failed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
                opening = false
            }
        }
    }

    ProviderEditorDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("provider-account-dialog"),
        title = { Text(stringResource(R.string.provider_account_manage_title, current.displayName)) },
        text = {
            AccountDialogContent(
                current = current,
                enabled = !busy && live != null,
                testing = busy && !opening,
                unavailable = unavailable && sameAccountTarget(resultTarget, current),
                failed = (failed && sameAccountTarget(resultTarget, current)) || live == null,
                onLogin = { perform(true) },
                onTest = { perform(false) },
            )
        },
        dismissButton = {},
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.testTag("provider-account-close"),
            ) { Text(stringResource(SharedR.string.chat_details_close)) }
        },
    )
}

private fun sameAccountTarget(
    target: ProviderRowUi?,
    current: ProviderRowUi,
): Boolean =
    target != null && target.accountState == current.accountState &&
        sameProviderEditableConfig(target.configuration, current.configuration)

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun AccountDialogContent(
    current: ProviderRowUi,
    enabled: Boolean,
    testing: Boolean,
    unavailable: Boolean,
    failed: Boolean,
    onLogin: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        current.accountState?.let(::managedAccountLabel) ?: R.string.provider_account_unknown,
                    ),
                    Modifier.testTag("provider-account-state"),
                )
                AccountConnectionStatus(current.status)
            }
        }
        OutlinedButton(
            onClick = onLogin,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("provider-account-login"),
        ) { Text(stringResource(R.string.provider_account_open_login)) }
        FilledTonalButton(
            onClick = onTest,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("provider-account-test"),
        ) {
            Text(
                stringResource(
                    if (testing) {
                        R.string.provider_testing
                    } else {
                        R.string.provider_account_connection_test
                    },
                ),
            )
        }
        if (unavailable) Text(stringResource(R.string.provider_subscription_runtime_unavailable))
        if (failed) {
            Text(
                stringResource(R.string.provider_account_operation_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("provider-account-operation-failed"),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountConnectionStatus(status: ConnectionTestStatus) {
    val tag =
        when (status) {
            ConnectionTestStatus.Untested -> "provider-account-untested"
            is ConnectionTestStatus.Passed -> "provider-account-passed"
            is ConnectionTestStatus.Failed -> "provider-account-failed"
        }
    val label =
        when (status) {
            ConnectionTestStatus.Untested -> {
                stringResource(R.string.conn_untested)
            }

            is ConnectionTestStatus.Passed -> {
                stringResource(R.string.provider_account_connected)
            }

            is ConnectionTestStatus.Failed -> {
                stringResource(
                    R.string.provider_account_failure,
                    stringResource(ConnectionTestMapping.phaseLabel(status.phase)),
                    stringResource(ConnectionTestMapping.codeLabel(status.code)),
                )
            }
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color =
            when (status) {
                ConnectionTestStatus.Untested -> MaterialTheme.colorScheme.surfaceVariant
                is ConnectionTestStatus.Passed -> MaterialTheme.colorScheme.primaryContainer
                is ConnectionTestStatus.Failed -> MaterialTheme.colorScheme.errorContainer
            },
    ) {
        Text(
            label,
            Modifier.testTag(tag).padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
