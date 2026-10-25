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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.core.storage.knowledge.KnowledgeNote
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "LongParameterList", "TooGenericExceptionCaught")
internal fun KnowledgeNoteEditor(
    note: KnowledgeNote,
    service: KnowledgeNoteService,
    modifier: Modifier,
    isNew: Boolean = false,
    onKnowledgeCreated: ((String) -> Unit)? = null,
    onReference: (suspend (List<com.helix.core.storage.repository.KnowledgeReference>) -> Boolean)? = null,
    referenceService: KnowledgeReferenceService? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(note.id) { mutableStateOf(note.title) }
    var body by rememberSaveable(note.id) { mutableStateOf(note.body) }
    var editing by rememberSaveable(note.id) { mutableStateOf(note.body.isBlank()) }
    var management by rememberSaveable(note.id) { mutableStateOf(false) }
    var sources by remember(note.id) { mutableStateOf(emptyList<KnowledgeNoteSource>()) }
    var failed by remember(note.id) { mutableStateOf(false) }
    var knowledgeDraft by remember(note.id) { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var tools by rememberSaveable(note.id) { mutableStateOf(false) }
    var showSources by rememberSaveable(note.id) { mutableStateOf(false) }
    val busy = note.generationState == "RUNNING"
    LaunchedEffect(note.id, note.updatedAt) { sources = service.sources(note.id) }
    val dirty = title != note.title || body != note.body

    fun goBack() {
        if (dirty) {
            leave = true
        } else if (editing && !isNew) {
            editing = false
        } else {
            onBack()
        }
    }

    fun save(
        reviewed: Boolean,
        exit: Boolean = false,
    ) {
        scope.launch {
            try {
                if (isNew) {
                    service.saveNew(
                        note,
                        title,
                        body,
                        reviewed,
                    )
                } else {
                    service.save(note.id, title, body, reviewed)
                }
                failed = false
                editing = false
                if (exit && isNew) onBack()
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
    if (knowledgeDraft) {
        KnowledgeDraftDialog(
            note,
            service,
            onDismiss = { knowledgeDraft = false },
            onSaved = { id ->
                knowledgeDraft = false
                if (onKnowledgeCreated !=
                    null
                ) {
                    onKnowledgeCreated(id)
                } else {
                    onBack()
                }
            },
        )
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
                androidx.compose.foundation.layout.FlowRow {
                    TextButton(onClick = {
                        leave = false
                        title = note.title
                        body = note.body
                        if (isNew) onBack() else editing = false
                    }) {
                        Text(stringResource(R.string.common_discard_edit))
                    }
                    TextButton(
                        onClick = { leave = false },
                    ) { Text(stringResource(R.string.knowledge_keep_editing)) }
                }
            },
        )
    }

    fun reference() {
        scope.launch {
            try {
                val selected =
                    requireNotNull(referenceService).select(
                        note.kind,
                        note.id,
                        if (note.body.length > 1800) "EXCERPT" else "FULL",
                    )
                if (onReference?.invoke(listOf(selected)) != true) failed = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            }
        }
    }
    if (management) {
        com.helix.app.ui.ConversationSheet(stringResource(R.string.knowledge_more), "knowledge-note-more", {
            management = false
        }) {
            KnowledgeNoteManagement(note, service, dirty)
            KnowledgeReferenceManagement(note, service, sources, dirty)
            if (note.kind == "KNOWLEDGE") {
                TextButton(onClick = {
                    save(note.state != "REVIEWED")
                    management = false
                }, enabled = !busy && !dirty) {
                    Text(stringResource(R.string.knowledge_review_toggle))
                }
            }
            if (note.generatedDraft.isNotBlank()) {
                TextButton(onClick = {
                    management = false
                    tools = true
                }) {
                    Text(stringResource(R.string.knowledge_model_tools))
                }
            }
        }
    }
    if (tools) {
        com.helix.app.ui.ConversationSheet(
            stringResource(
                R.string.knowledge_model_tools,
            ),
            "knowledge-saved-suggestion",
            {
                tools = false
            },
        ) {
            Text(stringResource(R.string.knowledge_generated_unreviewed))
            SelectionContainer {
                com.helix.app.ui
                    .MarkdownText(note.generatedDraft)
            }
            TextButton(onClick = {
                body = note.generatedDraft
                editing = true
                tools = false
            }, enabled = note.generatedDraft.isNotBlank(), modifier = Modifier.testTag("knowledge-load-draft")) {
                Text(stringResource(R.string.knowledge_load_draft))
            }
        }
    }
    if (showSources) {
        com.helix.app.ui.ConversationSheet(
            stringResource(
                R.string.knowledge_source_details,
            ),
            "knowledge-note-sources",
            {
                showSources = false
            },
        ) {
            val origins =
                com.helix.core.storage.repository.KnowledgeReferences
                    .decode(note.originReferencesJson)
            if (note.originMessageId.isNotBlank()) Text(stringResource(R.string.knowledge_reply_provenance))
            if (origins.isNotEmpty()) KnowledgeReferenceChips(origins)
            sources.forEach { source ->
                Text(source.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(source.excerpt) }
                Text(source.url, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }
    }
    Column(modifier) {
        KnowledgeHeader(
            stringResource(if (editing) R.string.knowledge_edit_mode else referenceKindLabel(note.kind)),
            "knowledge-note-back",
            ::goBack,
            onMore = if (!editing) ({ management = true }) else null,
            onSave = if (editing) ({ save(false) }) else null,
            saveEnabled = !busy && title.isNotBlank(),
        )
        if (failed) Text(stringResource(R.string.knowledge_management_failed))
        LazyColumn(
            Modifier.weight(1f).testTag("knowledge-note-editor"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (editing) {
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
                        minLines = 6,
                        maxLines = 16,
                        label = { Text(stringResource(R.string.knowledge_markdown_body)) },
                        modifier = Modifier.fillMaxWidth().testTag("knowledge-note-body"),
                    )
                }
            } else {
                item { Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium) }
                item {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            knowledgeDate(note.updatedAt),
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            stringResource(
                                if (note.kind == "NOTE") {
                                    R.string.knowledge_note_saved
                                } else {
                                    if (note.state ==
                                        "REVIEWED"
                                    ) {
                                        R.string.knowledge_note_reviewed
                                    } else {
                                        R.string.knowledge_note_unreviewed
                                    }
                                },
                            ),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                item {
                    SelectionContainer {
                        com.helix.app.ui.MarkdownText(
                            body.ifBlank { stringResource(R.string.knowledge_note_blank) },
                            Modifier.fillMaxWidth().testTag("knowledge-note-preview"),
                        )
                    }
                }
                if (sources.isNotEmpty() || note.originReferencesJson != "[]" || note.originMessageId.isNotBlank()) {
                    item {
                        TextButton(
                            onClick = { showSources = true },
                            modifier = Modifier.testTag("knowledge-note-sources"),
                        ) {
                            Text(stringResource(R.string.knowledge_source_details))
                        }
                    }
                }
                if (note.generatedDraft.isNotBlank()) {
                    item {
                        TextButton(onClick = { tools = true }) {
                            Text(stringResource(R.string.knowledge_model_tools))
                        }
                    }
                }
            }
        }
        if (!editing) {
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onReference != null && referenceService != null) {
                    Button(
                        ::reference,
                        shape = KnowledgeActionShape,
                        enabled = !busy && note.body.isNotBlank(),
                        modifier = Modifier.testTag("knowledge-note-reference"),
                    ) {
                        Text(stringResource(R.string.knowledge_ask_content))
                    }
                }
                TextButton(onClick = { editing = true }, modifier = Modifier.testTag("knowledge-note-toggle-mode")) {
                    Text(stringResource(R.string.knowledge_edit_mode))
                }
                if (note.kind == "NOTE") {
                    TextButton(
                        onClick = { knowledgeDraft = true },
                        enabled = !busy && body.isNotBlank(),
                        modifier = Modifier.testTag("knowledge-create-knowledge"),
                    ) {
                        Text(stringResource(R.string.knowledge_make_knowledge))
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.knowledge_edit_hint),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}
