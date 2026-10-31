package com.helix.app.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.crypto.AEADBadTagException

// Declarative operation states; errors are displayed, never converted to successful backup results.
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
fun AppBackupEntry(
    service: AppBackupService,
    onCloseApp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var workspaces by remember { mutableStateOf(false) }
    var credentials by remember { mutableStateOf(false) }
    // Passwords never enter SavedState, logs or persistent preferences.
    var password by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<PreparedProfileRestore?>(null) }
    var exporting by remember { mutableStateOf<PreparedAppBackup?>(null) }
    var busy by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val cleanup = remember { arrayOfNulls<AutoCloseable>(2) }

    DisposableEffect(service) {
        onDispose {
            job?.cancel()
            cleanup[0]?.close()
            cleanup[1]?.close()
        }
    }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        outcome = null
        job =
            scope.launch {
                try {
                    block()
                } catch (
                    cancel: CancellationException,
                ) {
                    throw cancel
                } catch (
                    _: AEADBadTagException,
                ) {
                    outcome = R.string.app_backup_bad_password
                } catch (failure: Exception) {
                    val active = failure.message in setOf("RESTORE_EXECUTIONS_ACTIVE", "RESTORE_TURNS_ACTIVE")
                    outcome = if (active) R.string.app_backup_active else R.string.app_backup_failed
                } finally {
                    password = ""
                    busy = false
                    committing = false
                }
            }
    }
    val exportContract = remember { ActivityResultContracts.CreateDocument("application/zip") }
    val exporter =
        rememberLauncherForActivityResult(exportContract) { uri ->
            picking = false
            if (uri != null) {
                action {
                    val snapshot = exporting ?: return@action
                    service.export(uri, snapshot)
                    snapshot.close()
                    exporting = null
                    cleanup[1] = null
                    outcome = R.string.app_backup_ready
                }
            } else {
                password = ""
            }
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            picking = false
            if (uri != null) {
                action {
                    val result = service.prepareRestore(uri, credentials, password.toCharArray())
                    prepared?.close()
                    prepared = result
                    cleanup[0] = result
                }
            } else {
                password = ""
            }
        }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("settings-app-backup")) {
        Text(stringResource(R.string.app_backup_title))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = {
                if (!busy && !picking) {
                    prepared?.close()
                    prepared = null
                    cleanup[0] = null
                    exporting?.close()
                    exporting = null
                    cleanup[1] = null
                    password = ""
                    open = false
                }
            },
            title = {
                val title =
                    if (prepared == null) {
                        R.string.app_backup_title
                    } else {
                        R.string.app_backup_restore_preview
                    }
                Text(stringResource(title))
            },
            text = {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val preview = prepared
                    val exportPreview = exporting
                    val small = MaterialTheme.typography.bodySmall
                    if (preview == null && exportPreview == null) {
                        Text(stringResource(R.string.app_backup_defaults))
                        BackupOption(library, { library = it }, R.string.app_backup_originals, !busy)
                        BackupOption(workspaces, { workspaces = it }, R.string.app_backup_workspaces, !busy)
                        BackupOption(credentials, {
                            credentials = it
                            if (!it) password = ""
                        }, R.string.app_backup_credentials, !busy)
                        if (credentials) {
                            OutlinedTextField(
                                password,
                                { password = it.take(1024) },
                                enabled = !busy,
                                singleLine = true,
                                label = { Text(stringResource(R.string.app_backup_password)) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions =
                                    androidx.compose.foundation.text.KeyboardOptions(
                                        autoCorrectEnabled = false,
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                                    ),
                            )
                        }
                        Text(stringResource(R.string.app_backup_privacy), style = small)
                    } else {
                        val summary =
                            if (preview != null) {
                                AppBackupCapture.overview(preview.state)
                            } else {
                                requireNotNull(exportPreview).overview
                            }
                        Text(
                            stringResource(
                                R.string.app_backup_counts,
                                summary.sessions,
                                summary.messages,
                                summary.experts,
                                summary.tasks,
                            ),
                        )
                        if (summary.missing > 0) {
                            Text(stringResource(R.string.app_backup_missing, summary.missing))
                        }
                        if (preview != null) {
                            val credentialLabel =
                                if (preview.restoresCredentials) {
                                    R.string.app_backup_credentials_verified
                                } else {
                                    R.string.app_backup_credentials_excluded
                                }
                            Text(stringResource(credentialLabel))
                            Text(stringResource(R.string.app_backup_replace))
                            Text(
                                stringResource(R.string.app_backup_public_conflict),
                                style = small,
                            )
                        } else {
                            Text(stringResource(R.string.app_backup_privacy), style = small)
                        }
                    }
                    outcome?.let { Text(stringResource(it), Modifier.testTag("app-backup-outcome")) }
                    if (busy) Text(stringResource(R.string.app_backup_working))
                }
            },
            confirmButton = {
                if (prepared != null) {
                    TextButton(
                        onClick = {
                            val preview = prepared ?: return@TextButton
                            committing = true
                            action {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                    service.queueRestore(preview)
                                    onCloseApp()
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("app-backup-confirm-restore"),
                    ) { Text(stringResource(R.string.app_backup_confirm)) }
                } else if (exporting != null) {
                    TextButton(onClick = {
                        picking = true
                        exporter.launch("helix-backup.zip")
                    }, enabled = !busy && !picking) {
                        Text(stringResource(R.string.app_backup_save))
                    }
                } else {
                    val enabled = !busy && !picking && (!credentials || password.length >= 12)
                    Column {
                        TextButton(onClick = {
                            action {
                                val selection = AppBackupSelection(library, workspaces, credentials)
                                val result = service.prepareExport(selection, password.toCharArray())
                                exporting = result
                                cleanup[1] = result
                            }
                        }, enabled = enabled) { Text(stringResource(R.string.app_backup_export)) }
                        TextButton(onClick = {
                            picking = true
                            importer.launch(arrayOf("application/zip", "application/octet-stream"))
                        }, enabled = enabled) {
                            Text(stringResource(R.string.app_backup_select))
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (busy) {
                        job?.cancel()
                    } else {
                        prepared?.close()
                        prepared = null
                        cleanup[0] = null
                        exporting?.close()
                        exporting = null
                        cleanup[1] = null
                        password = ""
                        open = false
                    }
                }, enabled = !committing && !picking) {
                    val label = if (busy) R.string.app_backup_cancel else R.string.app_backup_close
                    Text(stringResource(label))
                }
            },
            modifier = Modifier.testTag("app-backup-dialog"),
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun BackupOption(
    value: Boolean,
    onChange: (Boolean) -> Unit,
    label: Int,
    enabled: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = value, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(value, onCheckedChange = null, enabled = enabled)
        Text(stringResource(label), Modifier.padding(start = 4.dp))
    }
}
