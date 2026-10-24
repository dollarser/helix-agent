package com.helix.app.knowledge

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.core.storage.knowledge.CollectedSource
import kotlinx.coroutines.launch
import org.json.JSONArray

@Composable
@Suppress("FunctionName")
fun KnowledgeNavigation(
    service: KnowledgeService,
    navigation: androidx.navigation.NavController,
) {
    val request by service.openRequests.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request != null) {
            navigation.navigate(com.helix.app.ShellDestination.Knowledge.route) {
                launchSingleTop = true
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod") // Sources/notes navigation and Compose callbacks.
fun KnowledgeScreen(
    service: KnowledgeService,
    onReference: (String) -> Unit,
    providers: com.helix.app.provider.ProviderService? = null,
) {
    val all by service.items.collectAsStateWithLifecycle(emptyList())
    val notes by service.noteService.notes.collectAsStateWithLifecycle(emptyList())
    val request by service.openRequests.collectAsStateWithLifecycle()
    val error by service.captureError.collectAsStateWithLifecycle()
    val deletionError by service.deletionError.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<CollectedSource>>(emptyList()) }
    var noteHits by remember { mutableStateOf(notes) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showQuestions by rememberSaveable { mutableStateOf(false) }
    var selectedNote by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSources by remember { mutableStateOf(emptySet<String>()) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val newNoteTitle = stringResource(R.string.knowledge_note_new)

    fun createNote(ids: List<String>) {
        scope.launch {
            try {
                selectedNote = service.noteService.create(ids, newNoteTitle)
                selectedSources = emptySet()
                selected = null
                showNotes = true
            } catch (
                _: android.database.sqlite.SQLiteException,
            ) {
                failed = true
            } catch (_: IllegalArgumentException) {
                failed = true
            }
        }
    }
    LaunchedEffect(request) {
        if (request != null) {
            selected = request
            selectedNote = null
            showQuestions = false
            showNotes = false
            service.openRequests.value = null
        }
    }
    LaunchedEffect(all) {
        selectedSources =
            selectedSources.intersect(all.filter { it.state != "DELETING" }.map { it.id }.toSet())
    }
    LaunchedEffect(query, all) { hits = if (query.isBlank()) all else service.search(query) }
    LaunchedEffect(query, notes) { noteHits = if (query.isBlank()) notes else service.noteService.search(query) }
    val item = all.firstOrNull { it.id == selected }
    val note = notes.firstOrNull { it.id == selectedNote }
    BackHandler(item != null && note == null) { selected = null }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (note != null || showQuestions || item != null) {
            Text(
                stringResource(if (note != null) R.string.knowledge_local_only else R.string.knowledge_privacy),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (deletionError) Text(stringResource(R.string.knowledge_management_failed))
        if (error) Text(stringResource(R.string.knowledge_capture_failed))
        if (failed) Text(stringResource(R.string.knowledge_capture_failed))
        if (note != null) {
            KnowledgeNoteEditor(note, service.noteService, providers, Modifier.weight(1f)) {
                selectedNote = null
                showNotes = true
            }
        } else if (showQuestions) {
            KnowledgeQuestionScreen(service.questionService, providers, { showQuestions = false }) { id ->
                selectedNote = id
                showQuestions = false
                showNotes = true
            }
        } else if (item == null) {
            KnowledgeLibrary(
                notesTab = showNotes,
                onTab = { showNotes = it },
                onQuestions = { showQuestions = true },
                query = query,
                onQuery = { query = it.take(200) },
                sources = hits,
                notes = noteHits,
                selected = selectedSources,
                onSelect = { id, checked ->
                    selectedSources =
                        if (!checked) {
                            selectedSources - id
                        } else if (selectedSources.size < 8) {
                            selectedSources + id
                        } else {
                            selectedSources
                        }
                },
                onSource = { selected = it },
                onNote = { selectedNote = it },
                controls = {
                    KnowledgeImportAction(service, if (showNotes) ({ createNote(emptyList()) }) else null)
                },
                selectionActions = {
                    KnowledgeSelectionActions(
                        selectedSources.size,
                        { selectedSources = emptySet() },
                        { createNote(selectedSources.toList()) },
                        {
                            scope.launch {
                                val chosen = all.filter { it.id in selectedSources }
                                if (chosen.isNotEmpty()) {
                                    onReference(
                                        chosen
                                            .map {
                                                service.citation(it, minOf(20_000, 24_000 / chosen.size))
                                            }.joinToString("\n\n"),
                                    )
                                }
                            }
                        },
                    ) { KnowledgeDeleteSources(service, all.filter { it.id in selectedSources }) }
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            TextButton(onClick = { selected = null }, modifier = Modifier.testTag("knowledge-back")) {
                Text(stringResource(R.string.knowledge_back))
            }
            KnowledgeDetail(item, service, onReference) { createNote(listOf(item.id)) }
        }
    }
}

@Composable
internal fun knowledgeStatus(item: CollectedSource): String =
    stringResource(
        when (item.state) {
            "READY" -> R.string.knowledge_ready
            "DELETING" -> R.string.knowledge_deleting
            "IMPORTING", "QUEUED", "EXTRACTING" -> R.string.knowledge_processing
            "FAILED" -> R.string.knowledge_failed
            else -> R.string.knowledge_incomplete
        },
    )

@Composable
@Suppress("FunctionName", "LongMethod")
private fun KnowledgeDetail(
    item: CollectedSource,
    service: KnowledgeService,
    onReference: (String) -> Unit,
    onDistill: () -> Unit,
) {
    if (item.state == "DELETING") {
        Text(stringResource(R.string.knowledge_deleting))
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    var summary by remember(item.id, item.summary) { mutableStateOf(item.summary) }
    var summaryFailed by remember { mutableStateOf(false) }
    val files = remember(item.filesJson) { JSONArray(item.filesJson) }
    val analysisPrompt = stringResource(R.string.knowledge_analysis_prompt)
    LazyColumn(Modifier.testTag("knowledge-detail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = onDistill, modifier = Modifier.testTag("knowledge-distill")) {
                Text(stringResource(R.string.knowledge_distill))
            }
        }
        item { Text(item.title, style = MaterialTheme.typography.headlineSmall) }
        item {
            SelectionContainer {
                Text(
                    listOf(
                        item.sourceUrl,
                        item.source,
                        java.time.Instant
                            .ofEpochMilli(item.receivedAt)
                            .toString(),
                    ).joinToString("\n"),
                )
            }
        }
        item { Text(knowledgeStatus(item)) }
        item { Text(stringResource(R.string.knowledge_source_disclosure)) }
        if (item.state == "INCOMPLETE" || item.state == "FAILED") {
            item { Text(stringResource(R.string.knowledge_limits)) }
        }
        if (item.detail.split(',').any { it in setOf("original-only", "no-readable-text") }) {
            item { Text(stringResource(R.string.knowledge_visual_reading_hint)) }
        }
        if (item.detail == "import-interrupted") item { Text(stringResource(R.string.knowledge_reshare)) }
        item { Text(stringResource(R.string.knowledge_original)) }
        item { SelectionContainer { Text(item.rawText, Modifier.testTag("knowledge-raw")) } }
        items(files.length()) { index ->
            val metadata = files.getJSONObject(index)
            TextButton(onClick = {
                try {
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            service.originalFile(item, index),
                        )
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, metadata.getString("mime"))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    openFailed = true
                }
            }) { Text(metadata.getString("name")) }
        }
        if (openFailed) item { Text(stringResource(R.string.knowledge_no_viewer)) }
        item { Text(stringResource(R.string.knowledge_extracted)) }
        item { SelectionContainer { Text(item.extracted, Modifier.testTag("knowledge-extracted")) } }
        if (item.state !in setOf("DELETING", "IMPORTING", "QUEUED", "EXTRACTING") &&
            item.detail != "import-interrupted"
        ) {
            item {
                TextButton(onClick = { service.retry(item) }, modifier = Modifier.testTag("knowledge-retry")) {
                    Text(stringResource(R.string.knowledge_retry))
                }
            }
        }
        item {
            Button(
                onClick = { scope.launch { onReference(service.citation(item)) } },
                modifier = Modifier.testTag("knowledge-reference"),
            ) {
                Text(stringResource(R.string.knowledge_reference))
            }
        }
        item { Text(stringResource(R.string.knowledge_analysis_disclosure)) }
        item {
            TextButton(
                onClick = { scope.launch { onReference(analysisPrompt + "\n\n" + service.citation(item)) } },
                modifier = Modifier.testTag("knowledge-analyze"),
            ) {
                Text(stringResource(R.string.knowledge_analyze))
            }
        }
        item {
            OutlinedTextField(
                summary,
                { summary = it.take(MAX_KNOWLEDGE_TEXT) },
                label = { Text(stringResource(R.string.knowledge_summary)) },
                modifier = Modifier.fillMaxWidth().testTag("knowledge-summary"),
            )
        }
        item {
            TextButton(onClick = {
                scope.launch {
                    try {
                        service.saveSummary(item.id, summary)
                        summaryFailed = false
                    } catch (_: android.database.sqlite.SQLiteException) {
                        summaryFailed = true
                    }
                }
            }, modifier = Modifier.testTag("knowledge-save-summary")) {
                Text(stringResource(R.string.knowledge_save_summary))
            }
        }
        if (summaryFailed) item { Text(stringResource(R.string.knowledge_capture_failed)) }
        item { KnowledgeDeleteSources(service, listOf(item)) }
    }
}
