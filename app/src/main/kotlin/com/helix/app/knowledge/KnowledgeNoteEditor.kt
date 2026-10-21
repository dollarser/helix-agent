package com.helix.app.knowledge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.provider.ProviderService
import com.helix.core.model.ProviderResidence
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod") // Compose state rendering and UI callbacks.
internal fun KnowledgeNoteEditor(
    note: KnowledgeNote,
    service: KnowledgeNoteService,
    providers: ProviderService?,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var sources by remember(note.id) { mutableStateOf(emptyList<KnowledgeNoteSource>()) }
    var failed by remember(note.id) { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var expandedSource by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pending by remember { mutableStateOf<PreparedKnowledgeAnalysis?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val busy = preparing || note.generationState == "RUNNING"
    val rows =
        providers
            ?.rows
            ?.collectAsStateWithLifecycle()
            ?.value
            .orEmpty()
    val generationFailure by service.failure.collectAsStateWithLifecycle()
    val analysis = remember(providers) { providers?.let(::KnowledgeAnalysis) }
    LaunchedEffect(note.id, note.updatedAt) { sources = service.sources(note.id) }
    val dirty = title != note.title || body != note.body

    fun goBack() {
        if (dirty) leave = true else onBack()
    }

    fun save(
        reviewed: Boolean,
        exit: Boolean = false,
    ) {
        scope.launch {
            try {
                service.save(note.id, title, body, reviewed)
                failed = false
                if (exit) onBack()
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (
                _: IllegalArgumentException,
            ) {
                failed = true
            } catch (
                _: IllegalStateException,
            ) {
                failed = true
            } catch (_: android.database.sqlite.SQLiteException) {
                failed = true
            }
        }
    }
    BackHandler { goBack() }
    if (leave) {
        AlertDialog(
            onDismissRequest = { leave = false },
            title = { Text(stringResource(R.string.knowledge_unsaved)) },
            confirmButton = {
                TextButton(onClick = {
                    leave = false
                    save(false, true)
                }) {
                    Text(stringResource(R.string.knowledge_save_draft_back))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { leave = false },
                ) { Text(stringResource(R.string.knowledge_keep_editing)) }
            },
        )
    }
    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text(stringResource(R.string.knowledge_choose_model)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    rows.filter { !it.managedExternally }.forEach { row ->
                        items(row.conversationModels.filter(row::modelSelectable)) { model ->
                            TextButton(onClick = {
                                target = row.id to model
                                showModels = false
                            }) {
                                Text("${row.displayName} · ${row.modelLabel(model)}\n${row.origin}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showModels = false },
                ) { Text(stringResource(R.string.knowledge_close)) }
            },
        )
    }
    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.knowledge_confirm_generation)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${request.destination.providerName}\n${request.destination.origin}\n${request.model}")
                    Text(
                        stringResource(
                            if (request.destination.residence == ProviderResidence.ON_DEVICE_LOCAL) {
                                R.string.knowledge_generation_local
                            } else {
                                R.string.knowledge_generation_remote
                            },
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.knowledge_generation_scope,
                            request.sources.size,
                            request.inputCharacters,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    preparing = true
                    scope.launch {
                        try {
                            service.generate(request).join()
                        } finally {
                            preparing = false
                        }
                    }
                }, modifier = Modifier.testTag("knowledge-confirm-generate")) {
                    Text(stringResource(R.string.knowledge_confirm_send))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pending = null },
                ) { Text(stringResource(R.string.knowledge_keep_editing)) }
            },
        )
    }
    LazyColumn(modifier.testTag("knowledge-note-editor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { KnowledgeNoteManagement(note, service, dirty) }
        item { KnowledgeReferenceManagement(note, service, sources, dirty) }
        item {
            TextButton(onClick = { goBack() }, modifier = Modifier.testTag("knowledge-note-back")) {
                Text(stringResource(R.string.knowledge_back))
            }
        }
        item {
            Text(
                stringResource(
                    if (note.state == "REVIEWED" && !dirty) {
                        R.string.knowledge_note_reviewed
                    } else {
                        R.string.knowledge_note_draft
                    },
                ),
            )
        }
        item {
            OutlinedTextField(
                title,
                { title = it.take(160) },
                enabled = !busy,
                label = { Text(stringResource(R.string.knowledge_note_title)) },
                modifier = Modifier.fillMaxWidth().testTag("knowledge-note-title"),
            )
        }
        item {
            OutlinedTextField(
                body,
                { body = it.take(MAX_KNOWLEDGE_TEXT) },
                enabled = !busy,
                minLines = 5,
                maxLines = 12,
                label = { Text(stringResource(R.string.knowledge_note_body)) },
                modifier = Modifier.fillMaxWidth().testTag("knowledge-note-body"),
            )
        }
        item {
            Button(
                onClick = { save(true) },
                enabled = !busy && title.isNotBlank(),
                modifier = Modifier.testTag("knowledge-note-save"),
            ) { Text(stringResource(R.string.knowledge_note_save)) }
        }
        item {
            TextButton(
                onClick = { save(false) },
                enabled = !busy,
                modifier = Modifier.testTag("knowledge-note-save-draft"),
            ) { Text(stringResource(R.string.knowledge_save_draft)) }
        }
        if (sources.isNotEmpty()) {
            item { Text(stringResource(R.string.knowledge_frozen_sources, sources.size)) }
            items(sources, key = { it.sourceId }) { source ->
                TextButton(onClick = {
                    expandedSource =
                        if (expandedSource ==
                            source.sourceId
                        ) {
                            null
                        } else {
                            source.sourceId
                        }
                }) {
                    Text("[source:${source.sourceId}] ${source.title}\n${source.url}")
                }
                if (expandedSource == source.sourceId) {
                    SelectionContainer {
                        Text(
                            "[source:${source.sourceId}] ${source.title}\n${source.url}\n${source.excerpt}",
                        )
                    }
                }
            }
            item {
                TextButton(
                    onClick = { showModels = true },
                    enabled = !busy && providers != null,
                    modifier = Modifier.testTag("knowledge-note-model"),
                ) {
                    Text(target?.second ?: stringResource(R.string.knowledge_choose_model))
                }
            }
            item { Text(stringResource(R.string.knowledge_generation_instructions)) }
            item {
                TextButton(onClick = {
                    val selected = target ?: return@TextButton
                    val engine = analysis ?: return@TextButton
                    preparing = true
                    scope.launch {
                        try {
                            service.save(note.id, title, body, false)
                            pending = service.prepare(note.id, engine, selected.first, selected.second)
                            failed = false
                        } catch (
                            cancel: CancellationException,
                        ) {
                            throw cancel
                        } catch (
                            _: IllegalArgumentException,
                        ) {
                            failed = true
                        } catch (
                            _: IllegalStateException,
                        ) {
                            failed = true
                        } catch (
                            _: android.database.sqlite.SQLiteException,
                        ) {
                            failed = true
                        } finally {
                            preparing = false
                        }
                    }
                }, enabled = !busy && target != null, modifier = Modifier.testTag("knowledge-note-generate")) {
                    Text(stringResource(R.string.knowledge_generate))
                }
            }
        }
        if (busy) item { Text(stringResource(R.string.knowledge_generating)) }
        if (failed || generationFailure == note.id || note.generationState in setOf("FAILED", "INTERRUPTED")) {
            item { Text(stringResource(R.string.knowledge_generation_failed)) }
        }
        if (note.generationState == "READY") {
            item { Text(stringResource(R.string.knowledge_generated_unreviewed)) }
            item {
                TextButton(
                    onClick = { body = note.generatedDraft },
                    modifier = Modifier.testTag("knowledge-load-draft"),
                ) {
                    Text(stringResource(R.string.knowledge_load_draft))
                }
            }
            item { SelectionContainer { Text(note.generatedDraft, Modifier.testTag("knowledge-generated-draft")) } }
        }
    }
}
