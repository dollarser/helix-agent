package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.helix.app.R
import com.helix.app.localmodel.LocalModelCatalog
import com.helix.app.localmodel.LocalModelCatalogEvidence
import com.helix.app.localmodel.LocalModelCatalogSource
import com.helix.app.localmodel.LocalModelInstallResult
import com.helix.app.localmodel.LocalModelTransferPhase
import com.helix.app.localmodel.LocalModelTransferProgress
import com.helix.app.provider.ProviderService
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Curated install-first UX; exact URL/hash/size remains available as an explicit advanced import. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "SwallowedException", "CyclomaticComplexMethod")
internal fun LocalModelDialog(
    providerService: ProviderService,
    currentSessionAvailable: Boolean,
    onUseCurrentSession: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(LocalModelCatalog.entries.first().id) }
    var source by remember { mutableStateOf(LocalModelCatalogSource.MODELSCOPE) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<LocalModelTransferProgress?>(null) }
    var result by remember { mutableStateOf<LocalModelInstallResult?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun startInstall() {
        failure = false
        result = null
        job =
            scope.launch {
                try {
                    result =
                        if (advanced) {
                            providerService.installManualLocalModel(
                                url.trim(),
                                hash.trim(),
                                size.toLong(),
                                name.trim(),
                            ) { update -> scope.launch { progress = update } }
                        } else {
                            providerService.installCuratedLocalModel(selectedId, source) { update ->
                                scope.launch { progress = update }
                            }
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    failure = true
                } finally {
                    job = null
                }
            }
    }

    val installEnabled =
        job == null &&
            if (advanced) {
                name.isNotBlank() &&
                    url.isNotBlank() &&
                    hash.length == 64 &&
                    size.toLongOrNull()?.let { it > 0 } == true
            } else {
                true
            }
    AlertDialog(
        onDismissRequest = {
            job?.cancel()
            onDismiss()
        },
        title = { Text(stringResource(R.string.local_model_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.local_model_description))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            advanced = false
                            failure = false
                            result = null
                        },
                        enabled = job == null,
                        modifier = Modifier.testTag("local-model-curated"),
                    ) { Text(stringResource(R.string.local_model_curated)) }
                    OutlinedButton(
                        onClick = {
                            advanced = true
                            failure = false
                            result = null
                        },
                        enabled = job == null,
                        modifier = Modifier.testTag("local-model-advanced"),
                    ) { Text(stringResource(R.string.local_model_advanced)) }
                }
                if (advanced) {
                    ManualLocalModelFields(
                        name,
                        { name = it },
                        url,
                        { url = it },
                        hash,
                        { hash = it },
                        size,
                        { size = it },
                        job,
                    )
                } else {
                    CuratedLocalModelFields(selectedId, { selectedId = it }, source, { source = it }, job)
                }
                progress?.let {
                    LinearProgressIndicator(
                        progress = { (it.downloadedBytes.toFloat() / it.totalBytes.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().testTag("local-model-progress"),
                    )
                    Text(
                        when (it.phase) {
                            LocalModelTransferPhase.DOWNLOADING -> {
                                stringResource(
                                    R.string.local_model_progress,
                                    formatBytes(it.downloadedBytes),
                                    formatBytes(it.totalBytes),
                                )
                            }

                            LocalModelTransferPhase.VERIFYING -> {
                                stringResource(R.string.local_model_verifying)
                            }

                            LocalModelTransferPhase.READY -> {
                                stringResource(R.string.local_model_ready)
                            }
                        },
                    )
                }
                if (failure) {
                    Text(stringResource(R.string.local_model_failed), Modifier.testTag("local-model-error"))
                }
                result?.let { installed ->
                    val connected = installed.connection is ProbeOutcome.Ok
                    val capabilityPassed = installed.capabilities is ProbeOutcome.Ok
                    Text(
                        stringResource(
                            if (connected) {
                                R.string.local_model_connection_passed
                            } else {
                                R.string.local_model_connection_failed
                            },
                        ),
                        Modifier.testTag("local-model-install-result"),
                    )
                    if (connected) {
                        Text(
                            stringResource(
                                if (capabilityPassed) {
                                    R.string.local_model_capability_passed
                                } else {
                                    R.string.local_model_capability_failed
                                },
                            ),
                        )
                    }
                    Text(
                        stringResource(R.string.local_model_use_current_only),
                        Modifier.testTag("local-model-session-scope-note"),
                    )
                    TextButton(
                        onClick = { onUseCurrentSession(installed.providerId, installed.modelId) },
                        enabled = currentSessionAvailable && connected,
                        modifier = Modifier.testTag("local-model-use-current"),
                    ) {
                        Text(stringResource(R.string.local_model_use_current))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("local-model-download"),
                enabled = installEnabled,
                onClick = ::startInstall,
            ) { Text(stringResource(R.string.local_model_install_test)) }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("local-model-cancel"),
                onClick = {
                    job?.cancel()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.chat_details_close)) }
        },
    )
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun ManualLocalModelFields(
    name: String,
    onName: (String) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    hash: String,
    onHash: (String) -> Unit,
    size: String,
    onSize: (String) -> Unit,
    job: Job?,
) {
    OutlinedTextField(
        name,
        onName,
        modifier = Modifier.testTag("local-model-name"),
        label = { Text(stringResource(R.string.local_model_name)) },
        enabled = job == null,
    )
    OutlinedTextField(
        url,
        onUrl,
        modifier = Modifier.testTag("local-model-url"),
        label = { Text(stringResource(R.string.local_model_url)) },
        enabled = job == null,
    )
    OutlinedTextField(
        hash,
        onHash,
        modifier = Modifier.testTag("local-model-hash"),
        label = { Text("SHA-256") },
        enabled = job == null,
    )
    OutlinedTextField(
        size,
        onSize,
        modifier = Modifier.testTag("local-model-size"),
        label = { Text(stringResource(R.string.local_model_size)) },
        enabled = job == null,
    )
}

@Composable
@Suppress("FunctionName")
private fun CuratedLocalModelFields(
    selectedId: String,
    onSelected: (String) -> Unit,
    source: LocalModelCatalogSource,
    onSource: (LocalModelCatalogSource) -> Unit,
    job: Job?,
) {
    LocalModelCatalog.entries.forEach { entry ->
        OutlinedButton(
            onClick = { onSelected(entry.id) },
            enabled = job == null,
            modifier = Modifier.fillMaxWidth().testTag("local-model-catalog-${entry.id}"),
        ) {
            val evidence =
                if (entry.evidence == LocalModelCatalogEvidence.FIXED_TASK_PASSED) {
                    stringResource(R.string.local_model_evidence_task)
                } else {
                    stringResource(R.string.local_model_evidence_compat)
                }
            Text(
                (if (entry.id == selectedId) "● " else "○ ") +
                    "${entry.displayName} · ${formatBytes(entry.sizeBytes)} · ${entry.license}\n$evidence",
            )
        }
    }
    Text(stringResource(R.string.local_model_source))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalModelCatalogSource.entries.forEach { candidate ->
            TextButton(
                onClick = { onSource(candidate) },
                enabled = job == null,
                modifier = Modifier.testTag("local-model-source-${candidate.name.lowercase()}"),
            ) {
                val label = if (candidate == LocalModelCatalogSource.MODELSCOPE) "ModelScope" else "Hugging Face"
                Text((if (candidate == source) "● " else "○ ") + label)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
        else -> "$bytes B"
    }
