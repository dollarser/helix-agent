package com.helix.app.knowledge

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun KnowledgeImportAction(
    service: KnowledgeService,
    onNewNote: (() -> Unit)? = null,
) {
    val enabled by service.agentAccess.enabled.collectAsStateWithLifecycle()
    val accessTitle = stringResource(R.string.knowledge_agent_access_title)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                service.capture(
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Document picker",
                )
            }
        }
    var details by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(false) }
    Column {
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
            if (onNewNote != null) {
                TextButton(onNewNote, modifier = Modifier.testTag("knowledge-new-note")) {
                    Text(stringResource(R.string.knowledge_new_note_action))
                }
            }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.testTag("knowledge-import")) {
                Text(stringResource(R.string.knowledge_import_files))
            }
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("knowledge-library-settings")) {
                Text(stringResource(R.string.knowledge_library_settings))
            }
        }
        Text(stringResource(R.string.knowledge_local_only), style = MaterialTheme.typography.bodySmall)
        if (details) {
            AlertDialog(
                onDismissRequest = { details = false },
                title = { Text(stringResource(R.string.knowledge_library_settings)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.knowledge_privacy), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(accessTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.Switch(
                                checked = enabled,
                                onCheckedChange = service.agentAccess::setEnabled,
                                modifier = Modifier.semantics { contentDescription = accessTitle },
                            )
                        }
                        Text(
                            stringResource(R.string.knowledge_agent_access),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { TextButton({ details = false }) { Text(stringResource(R.string.knowledge_close)) } },
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun KnowledgeDeleteSources(
    service: KnowledgeService,
    sources: List<CollectedSource>,
) {
    var pending by remember { mutableStateOf<List<CollectedSource>?>(null) }
    KnowledgeConfirmedAction(
        label = stringResource(R.string.knowledge_delete_sources),
        disclosure = stringResource(R.string.knowledge_delete_sources_warning),
        enabled = sources.isNotEmpty(),
        capture = { pending = sources.toList() },
    ) { pending.orEmpty().forEach { service.remove(it.id, it) } }
}

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught") // Surface deletion failures; never turn them into success.
private fun KnowledgeConfirmedAction(
    label: String,
    disclosure: String,
    enabled: Boolean,
    capture: () -> Unit = {},
    action: suspend () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        capture()
        confirm = true
    }, enabled = enabled && !busy) { Text(label) }
    if (failed) Text(stringResource(R.string.knowledge_management_failed))
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(label) },
            text = { Text(disclosure) },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        try {
                            action()
                            failed = false
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(label) }
            },
        )
    }
}

@Composable
// SAF write failures remain visible; cancellation is not success.
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun KnowledgeNoteManagement(
    note: KnowledgeNote,
    service: KnowledgeNoteService,
    dirty: Boolean,
) {
    var expected by remember { mutableStateOf(note) }
    KnowledgeConfirmedAction(
        stringResource(R.string.knowledge_delete_note),
        stringResource(R.string.knowledge_delete_note_warning),
        !dirty && note.generationState != "RUNNING",
        { expected = note },
    ) { service.management.remove(expected) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var export by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val documentContract = remember { ActivityResultContracts.CreateDocument("text/markdown") }
    val destination =
        rememberLauncherForActivityResult(documentContract) { uri ->
            val frozen = export
            export = null
            if (uri != null && frozen == null) failed = true
            if (uri != null && frozen != null) {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                            output.bufferedWriter().use { it.write(frozen) }
                        }
                        failed = false
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed = true
                    }
                }
            }
        }
    TextButton(onClick = {
        scope.launch {
            try {
                export = service.management.export(note)
                destination.launch("helix-note.md")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            }
        }
    }, enabled = !dirty && note.generationState != "RUNNING") { Text(stringResource(R.string.knowledge_export_note)) }
    if (dirty) Text(stringResource(R.string.knowledge_save_before_manage))
    if (failed) Text(stringResource(R.string.knowledge_management_failed))
}

@Composable
@Suppress("FunctionName")
internal fun KnowledgeReferenceManagement(
    note: KnowledgeNote,
    service: KnowledgeNoteService,
    sources: List<com.helix.core.storage.knowledge.KnowledgeNoteSource>,
    dirty: Boolean,
) {
    val changes by service.management.sourceChanges.collectAsStateWithLifecycle(emptyList())
    var changed by remember(note.id) { mutableStateOf(false) }
    var expected by remember { mutableStateOf(note) }
    var frozen by remember { mutableStateOf(sources) }
    androidx.compose.runtime.LaunchedEffect(sources, changes) { changed = service.management.changed(sources) }
    if (changed) {
        Text(stringResource(R.string.knowledge_references_changed))
        KnowledgeConfirmedAction(
            stringResource(R.string.knowledge_refresh_references),
            stringResource(R.string.knowledge_refresh_references_warning),
            !dirty && note.generationState != "RUNNING",
            {
                expected = note
                frozen = sources.toList()
            },
        ) { service.management.refresh(expected, frozen) }
    }
}
