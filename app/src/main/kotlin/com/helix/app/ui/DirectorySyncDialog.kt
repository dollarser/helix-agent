package com.helix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.helix.app.R
import com.helix.app.files.DirectorySyncRule
import com.helix.app.files.DirectorySyncService
import com.helix.app.files.FileManagerService
import com.helix.app.files.FileSource
import com.helix.app.files.SyncRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.helix.feature.conversation.R as ConversationR
import com.helix.ui.shared.R as SharedR

// Failures remain visible; cancellation propagates.
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun DirectorySyncDialog(
    files: FileManagerService,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf<DirectorySyncService?>(null) }
    var sources by remember { mutableStateOf(emptyList<FileSource>()) }
    var rules by remember { mutableStateOf(emptyList<DirectorySyncRule>()) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(SyncRoot(files.defaultSource.scopeId, "work")) }
    var right by remember { mutableStateOf<SyncRoot?>(null) }
    var leftValid by remember { mutableStateOf(true) }
    var rightValid by remember { mutableStateOf(false) }

    fun run(action: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            failed = false
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    SyncPolling(files, retry, { sources = it }, { service = it }, { rules = it }, { failed = it })
    BackHandler { onDismiss() }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(
                Modifier.padding(16.dp).testTag("directory-sync"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleLarge)
                        TextButton(onDismiss) { Text(stringResource(ConversationR.string.files_close)) }
                    }
                    Text(stringResource(R.string.sync_summary), style = MaterialTheme.typography.bodySmall)
                }
                item {
                    SyncRootEditor(stringResource(R.string.sync_left), sources, left, !busy, { leftValid = it }) {
                        left =
                            it
                    }
                    SyncRootEditor(stringResource(R.string.sync_right), sources, right, !busy, { rightValid = it }) {
                        right =
                            it
                    }
                    TextButton(
                        onClick = {
                            right?.let { target ->
                                val source = left
                                run { requireNotNull(service).create(source, target) }
                            }
                        },
                        enabled = !busy && service != null && right != null && leftValid && rightValid,
                        modifier = Modifier.testTag("sync-create"),
                    ) { Text(stringResource(R.string.sync_add)) }
                    if (failed) {
                        Text(stringResource(R.string.sync_error), color = MaterialTheme.colorScheme.error)
                        TextButton({ retry++ }) { Text(stringResource(SharedR.string.chat_retry)) }
                    }
                    HorizontalDivider()
                }
                items(rules, key = { it.id }) { rule ->
                    SyncRuleRow(
                        rule,
                        busy,
                        run = { run { requireNotNull(service).run(rule.id) } },
                        toggle = { enabled ->
                            scope.launch { toggleSync(requireNotNull(service), rule.id, enabled) { failed = true } }
                        },
                        remove = { run { requireNotNull(service).remove(rule.id) } },
                    )
                }
            }
        }
    }
}

// Retry is explicit; failed loading never becomes an empty success.
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
private fun SyncPolling(
    files: FileManagerService,
    retry: Int,
    sources: (List<FileSource>) -> Unit,
    ready: (DirectorySyncService) -> Unit,
    rules: (List<DirectorySyncRule>) -> Unit,
    failed: (Boolean) -> Unit,
) {
    LaunchedEffect(files, retry) {
        failed(false)
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    files.sources().filter {
                        it.supportsMutation && (
                            it.scopeId == "app" ||
                                it.scopeId.startsWith(
                                    "saf-",
                                )
                        )
                    } to
                        requireNotNull(files.directorySync).value
                }
            sources(loaded.first)
            ready(loaded.second)
            while (isActive) {
                rules(withContext(Dispatchers.IO) { loaded.second.list() })
                delay(500)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed(true)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SyncRootEditor(
    label: String,
    sources: List<FileSource>,
    root: SyncRoot?,
    enabled: Boolean,
    valid: (Boolean) -> Unit,
    select: (SyncRoot) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var path by remember(root?.scope) { mutableStateOf(root?.path ?: "") }
    Column {
        TextButton({ expanded = true }, enabled = enabled) {
            Text(
                "$label · ${sources.find { it.scopeId == root?.scope }?.displayName ?: stringResource(
                    R.string.sync_select,
                )}",
            )
        }
        DropdownMenu(expanded, { expanded = false }) {
            sources.forEach { source ->
                DropdownMenuItem(text = { Text(source.displayName) }, onClick = {
                    expanded = false
                    // The editor keeps invalid text local until a canonical non-root directory is provided.
                    valid(true)
                    select(SyncRoot(source.scopeId, if (source.scopeId.startsWith("saf-")) "" else "work"))
                })
            }
        }
        OutlinedTextField(
            path,
            { value ->
                path = value
                if (root != null) {
                    val canonical = runCatching { SyncRoot(root.scope, value) }.getOrNull()
                    valid(canonical != null)
                    if (canonical != null) select(canonical)
                }
            },
            label = { Text(stringResource(R.string.sync_relative_path)) },
            enabled = enabled && root != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun SyncRuleRow(
    rule: DirectorySyncRule,
    busy: Boolean,
    run: () -> Unit,
    toggle: (Boolean) -> Unit,
    remove: () -> Unit,
) {
    val status =
        when (rule.status) {
            "COMPLETE" -> R.string.sync_complete
            "RUNNING" -> R.string.sync_running
            "READY" -> R.string.sync_ready
            "CONFLICT" -> R.string.sync_conflict
            "REVIEW" -> R.string.sync_review
            else -> R.string.sync_paused
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${rule.left.scope}:${rule.left.path} ↔ ${rule.right.scope}:${rule.right.path}")
        Text(stringResource(status), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rule.conflicts.isNotEmpty()) {
            Text(rule.conflicts.take(5).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sync_resolve), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow {
            TextButton(
                run,
                modifier = Modifier.testTag("sync-now-${rule.id}"),
                enabled =
                    !busy && rule.status != "RUNNING",
            ) { Text(stringResource(R.string.sync_now)) }
            TextButton({ toggle(rule.status != "RUNNING" && !rule.enabled) }) {
                val pause = rule.enabled || rule.status == "RUNNING"
                Text(stringResource(if (pause) R.string.sync_pause else R.string.sync_enable))
            }
            TextButton(
                remove,
                enabled = !busy && rule.status != "RUNNING",
            ) { Text(stringResource(R.string.files_delete)) }
        }
        HorizontalDivider()
    }
}

@Suppress("TooGenericExceptionCaught") // Persistence failure is shown; cancellation remains cancellation.
private suspend fun toggleSync(service: DirectorySyncService, id: String, enabled: Boolean, fail: () -> Unit) {
    try {
        withContext(Dispatchers.IO) { service.enable(id, enabled) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        fail()
    }
}
