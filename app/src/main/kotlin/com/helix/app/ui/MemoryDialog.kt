package com.helix.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import com.helix.app.memory.MemoryService
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.workspace.memory.MemoryEntry
import com.helix.core.workspace.memory.MemoryScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress(
    "FunctionName",
    "LongMethod",
    "CyclomaticComplexMethod",
    "TooGenericExceptionCaught",
)
internal fun MemoryDialog(
    service: MemoryService,
    sessionId: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedScope by remember { mutableStateOf<MemoryScope>(MemoryScope.Global) }
    var entries by remember { mutableStateOf(emptyList<MemoryEntry>()) }
    var name by remember { mutableStateOf("user.md") }
    var markdown by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("new") }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(service.enabled) }
    var autoGlobal by remember { mutableStateOf(service.autoGlobal) }
    var confirmDelete by remember { mutableStateOf(false) }
    val act: (suspend () -> Unit) -> Unit = { work ->
        if (!busy) {
            scope.launch {
                busy = true
                failed = false
                try {
                    work()
                } catch (
                    cancelled: kotlinx.coroutines.CancellationException,
                ) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        }
    }
    val reload: suspend () -> Unit = {
        entries =
            withContext(Dispatchers.IO) {
                if (query.isBlank()) service.list(selectedScope) else service.search(selectedScope, query)
            }
    }
    LaunchedEffect(selectedScope) {
        name = "user.md"
        markdown = ""
        hash = "new"
        act { reload() }
    }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                act {
                    markdown = withContext(Dispatchers.IO) { service.importDocument(uri) }
                    name = "imported.md"
                    hash = "new"
                }
            }
        }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            if (uri != null) act { withContext(Dispatchers.IO) { service.exportDocument(uri, selectedScope, name) } }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).indicatedVerticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.memory_intro))
                Row {
                    Checkbox(enabled, enabled = !busy, onCheckedChange = { value ->
                        act {
                            withContext(Dispatchers.IO) { service.configure("enabled", value) }
                            enabled = service.enabled
                        }
                    }, modifier = Modifier.testTag("memory-enabled"))
                    Text(stringResource(R.string.memory_enabled))
                }
                Row {
                    Checkbox(autoGlobal, enabled = !busy, onCheckedChange = { value ->
                        act {
                            withContext(Dispatchers.IO) { service.configure("auto-global", value) }
                            autoGlobal = service.autoGlobal
                        }
                    }, modifier = Modifier.testTag("memory-auto-global"))
                    Text(stringResource(R.string.memory_auto_global))
                }
                Row {
                    TextButton({ selectedScope = MemoryScope.Global }, enabled = !busy) {
                        Text(stringResource(R.string.memory_global))
                    }
                    TextButton(
                        { selectedScope = service.scope("project", sessionId) },
                        enabled = !busy && service.projectAvailable(sessionId),
                    ) {
                        Text(stringResource(R.string.memory_project))
                    }
                }
                if (!service.projectAvailable(sessionId)) Text(stringResource(R.string.memory_project_unavailable))
                OutlinedTextField(
                    query,
                    { query = it.take(256) },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.memory_search)) },
                    modifier = Modifier.testTag("memory-query"),
                )
                TextButton({ act { reload() } }, enabled = !busy, modifier = Modifier.testTag("memory-search")) {
                    Text(stringResource(R.string.memory_search))
                }
                entries.forEach { entry ->
                    TextButton(
                        {
                            act {
                                val current = withContext(Dispatchers.IO) { service.read(selectedScope, entry.path) }
                                name = current.path
                                markdown = current.markdown
                                hash = current.hash
                            }
                        },
                        enabled = !busy,
                        modifier =
                            Modifier.testTag(
                                "memory-entry-${entry.path}",
                            ),
                    ) { Text(entry.path) }
                }
                TextButton({
                    name = "new.md"
                    markdown = ""
                    hash = "new"
                }, enabled = !busy) {
                    Text(stringResource(R.string.memory_new))
                }
                OutlinedTextField(
                    name,
                    {
                        name = it
                        hash = "new"
                    },
                    enabled = !busy && hash == "new",
                    label = { Text(stringResource(R.string.memory_filename)) },
                    modifier = Modifier.testTag("memory-path"),
                )
                OutlinedTextField(
                    markdown,
                    { markdown = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.memory_markdown)) },
                    modifier = Modifier.heightIn(max = 240.dp).testTag("memory-markdown"),
                )
                Row {
                    TextButton({
                        act {
                            val saved =
                                withContext(Dispatchers.IO) { service.save(selectedScope, name, markdown, hash) }
                            hash = saved.hash
                            markdown = saved.markdown
                            reload()
                        }
                    }, enabled = !busy && markdown.isNotBlank(), modifier = Modifier.testTag("memory-save")) {
                        Text(stringResource(R.string.memory_save))
                    }
                    TextButton(
                        { confirmDelete = true },
                        enabled = !busy && hash != "new",
                        modifier = Modifier.testTag("memory-delete"),
                    ) { Text(stringResource(R.string.memory_delete)) }
                }
                Row {
                    TextButton({ importer.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) {
                        Text(stringResource(R.string.memory_import))
                    }
                    TextButton({ exporter.launch(name) }, enabled = !busy && hash != "new") {
                        Text(stringResource(R.string.memory_export))
                    }
                }
                if (failed) Text(stringResource(R.string.memory_failed), modifier = Modifier.testTag("memory-error"))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.files_close)) } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.memory_delete)) },
            text = { Text(name) },
            confirmButton = {
                TextButton(
                    {
                        confirmDelete = false
                        act {
                            withContext(Dispatchers.IO) { service.delete(selectedScope, name, hash) }
                            markdown = ""
                            hash = "new"
                            reload()
                        }
                    },
                    modifier =
                        Modifier.testTag(
                            "memory-delete-confirm",
                        ),
                ) { Text(stringResource(R.string.memory_delete)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
