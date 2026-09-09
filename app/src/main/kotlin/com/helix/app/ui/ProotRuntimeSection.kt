package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.proot.ProotToolModule
import com.helix.app.proot.ProotVerificationNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The PRoot Runtime section (HXA-085, developer + Advanced only): the stable
 * availability states the roadmap mandates (未安装 / 未验证 / 被禁用或强制停止 / 已验证)
 * + the two USER-CLICK actions — "验证 Runtime" (the only zero-Job bind; the process
 * is NOT a condition for tool availability) and "修复 Runtime" (the only repair-
 * activity path). No passive re-verification, no bind on entry.
 */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun ProotRuntimeSection() {
    val scope = rememberCoroutineScope()
    val rebaselineSuccess = stringResource(R.string.settings_proot_rebaseline_success)
    val rebaselineEmpty = stringResource(R.string.settings_proot_rebaseline_empty)
    var statusText by remember { mutableStateOf("…") }
    // The last user-click verification result (HXA-087 需更新 detection): the gate
    // label is bind-free and cannot see a moved lock; only the explicit
    // "验证 Runtime" click reveals a LOCK_MISMATCH and offers the re-baseline.
    var verifyNote by remember { mutableStateOf<ProotVerificationNote?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        statusText = ProotToolModule.verifyStatusLabel()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { refresh() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_proot_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_proot_status, statusText),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings-proot-status"),
        )
        verifyNote?.let { note ->
            Text(
                note.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings-proot-verify-note"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            verifyNote = ProotToolModule.verifyNowNote()
                            refresh()
                        }
                        busy = false
                    }
                },
                modifier = Modifier.testTag("settings-proot-verify"),
            ) {
                Text(stringResource(R.string.settings_proot_verify))
            }
            OutlinedButton(
                onClick = { ProotToolModule.openRepair() },
                modifier = Modifier.testTag("settings-proot-repair"),
            ) {
                Text(stringResource(R.string.settings_proot_repair))
            }
            OutlinedButton(
                onClick = { ProotToolModule.openLegalPage() },
                modifier = Modifier.testTag("settings-proot-legal"),
            ) {
                Text(stringResource(R.string.settings_proot_legal))
            }
            OutlinedButton(
                onClick = { showRemoveConfirm = true },
                modifier = Modifier.testTag("settings-proot-remove"),
            ) {
                Text(stringResource(R.string.settings_proot_remove))
            }
        }
        if (verifyNote?.needsRebaseline == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val existed = ProotToolModule.rebaseline()
                            verifyNote =
                                if (existed) {
                                    ProotVerificationNote(rebaselineSuccess)
                                } else {
                                    ProotVerificationNote(rebaselineEmpty)
                                }
                            refresh()
                        }
                    },
                    modifier = Modifier.testTag("settings-proot-rebaseline"),
                ) {
                    Text(stringResource(R.string.settings_proot_rebaseline))
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.settings_proot_remove_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_proot_remove_body),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirm = false
                        scope.launch(Dispatchers.IO) {
                            verifyNote = ProotToolModule.removeRuntimeNote()
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.settings_proot_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
