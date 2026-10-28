package com.helix.app.knowledge

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.core.storage.knowledge.CollectedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray

@Composable
@Suppress("FunctionName")
internal fun KnowledgeNavigation(
    service: KnowledgeService,
    navigation: androidx.navigation.NavController,
    departure: com.helix.app.ui.NavigationDeparture,
) {
    val observedRequest by service.openRequests.collectAsStateWithLifecycle()
    val request = observedRequest
    val entry by navigation.currentBackStackEntryFlow.collectAsStateWithLifecycle(initialValue = null)
    if (entry?.destination?.route != com.helix.app.ShellDestination.Knowledge.route) {
        com.helix.app.ui.RequestedPageNavigation(
            request,
            com.helix.app.ShellDestination.Knowledge.route,
            navigation,
            departure,
            onCancelled = { service.openRequests.compareAndSet(request, null) },
        )
    }
}

// Independent UI jobs must each propagate cancellation rather than turn it into a storage error.
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "ThrowsCount")
fun KnowledgeScreen(
    service: KnowledgeService,
    onReference: suspend (List<com.helix.core.storage.repository.KnowledgeReference>) -> Boolean,
) {
    val all by service.items.collectAsStateWithLifecycle(emptyList())
    val loadedNotes by service.noteService.notes.collectAsStateWithLifecycle(initialValue = null)
    val notes = loadedNotes.orEmpty()
    val request by service.openRequests.collectAsStateWithLifecycle()
    val error by service.captureError.collectAsStateWithLifecycle()
    val deletionError by service.deletionError.collectAsStateWithLifecycle()
    val storageError by service.storageError.collectAsStateWithLifecycle()
    val pendingSaves by service.pendingSaves.collectAsStateWithLifecycle()
    var showPendingSaves by rememberSaveable { mutableStateOf(false) }
    var recoveryFailed by remember { mutableStateOf(false) }
    var recoveryBusy by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(service) {
        val refresh = refreshScope.launch { service.refresh() }
        onPauseOrDispose { refresh.cancel() }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<CollectedSource>>(emptyList()) }
    var noteHits by remember { mutableStateOf(notes) }
    var section by rememberSaveable { mutableStateOf("NOTE") }
    var showQuestions by rememberSaveable { mutableStateOf(false) }
    var selectedNote by rememberSaveable { mutableStateOf<String?>(null) }
    var newNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var newNoteKind by rememberSaveable { mutableStateOf("NOTE") }
    var newNoteCreatedAt by rememberSaveable { mutableStateOf(0L) }
    var selectedSources by remember { mutableStateOf(emptySet<String>()) }
    var selectingSources by rememberSaveable { mutableStateOf(false) }
    var candidateIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var candidateHashes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var failed by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (showPendingSaves) {
        KnowledgePendingSaves(pendingSaves, recoveryFailed, recoveryBusy, { token ->
            scope.launch {
                recoveryBusy = true
                try {
                    selectedNote = service.recoverSave(token)
                    showPendingSaves = false
                    recoveryFailed = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    recoveryFailed = true
                } finally {
                    recoveryBusy = false
                }
            }
        }, { showPendingSaves = false })
    }

    fun createNote(
        ids: List<String>,
        requestedKind: String = "NOTE",
    ) {
        if (ids.isEmpty()) {
            newNoteId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            newNoteKind = requestedKind
            newNoteCreatedAt = System.currentTimeMillis()
            selectedNote = newNoteId
            selected = null
            return
        }
        candidateIds = ids
        candidateHashes =
            ids.map { id ->
                val source = all.first { it.id == id }
                knowledgeHash(source.extracted.ifBlank { source.rawText })
            }
    }
    if (candidateIds.isNotEmpty()) {
        KnowledgeSourceDraftDialog(
            candidateIds,
            candidateHashes,
            candidateIds.mapNotNull { id -> all.firstOrNull { it.id == id } },
            service.noteService,
            onDismiss = { candidateIds = emptyList() },
            onSaved = { id ->
                candidateIds = emptyList()
                selectedSources = emptySet()
                selectingSources = false
                selected = null
                selectedNote = id
                section = "KNOWLEDGE"
            },
        )
    }
    val departure = com.helix.app.ui.LocalNavigationDeparture.current
    LaunchedEffect(request) {
        val id = request ?: return@LaunchedEffect
        val open = {
            selected = id
            selectedNote = null
            showQuestions = false
            section = "SOURCE"
            service.openRequests.compareAndSet(id, null)
            Unit
        }
        if (departure == null) {
            open()
        } else {
            departure.request(
                { service.openRequests.value == id },
                cancelled = { service.openRequests.compareAndSet(id, null) },
                navigate = open,
            )
        }
    }
    LaunchedEffect(all) {
        selectedSources =
            selectedSources.intersect(all.filter { it.state != "DELETING" }.map { it.id }.toSet())
    }
    LaunchedEffect(query, all, notes) {
        try {
            hits = if (query.isBlank()) all else service.search(query)
            noteHits = if (query.isBlank()) notes else service.noteService.search(query)
            searchFailed = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            searchFailed = true
        }
    }
    val item = all.firstOrNull { it.id == selected }
    val storedNote = notes.firstOrNull { it.id == selectedNote }
    val isNew = storedNote == null && selectedNote != null && selectedNote == newNoteId
    val note =
        storedNote ?: if (isNew) {
            com.helix.core.storage.knowledge.KnowledgeNote(
                requireNotNull(newNoteId),
                "",
                "",
                newNoteCreatedAt,
                newNoteCreatedAt,
                kind = newNoteKind,
            )
        } else {
            null
        }
    BackHandler(item != null && note == null) { selected = null }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pendingSaves.isNotEmpty()) {
            TextButton({ showPendingSaves = true }, Modifier.testTag("knowledge-pending-open")) {
                Text(stringResource(R.string.knowledge_pending_title))
            }
        }
        if (storageError) {
            Text(stringResource(R.string.knowledge_storage_unavailable))
            TextButton(
                { refreshScope.launch { service.retryStartup() } },
            ) { Text(stringResource(R.string.knowledge_storage_retry)) }
        }
        if (deletionError) Text(stringResource(R.string.knowledge_management_failed))
        if (error) Text(stringResource(R.string.knowledge_capture_failed))
        if (failed) Text(stringResource(R.string.knowledge_capture_failed))
        if (searchFailed) Text(stringResource(R.string.knowledge_storage_unavailable))
        if (selectedNote != null && loadedNotes == null && !isNew) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.testTag("knowledge-note-loading"))
        } else if (note != null) {
            KnowledgeNoteEditor(
                note,
                service.noteService,
                Modifier.weight(1f),
                isNew = isNew,
                onKnowledgeCreated = { id ->
                    selectedNote = id
                    section = "KNOWLEDGE"
                },
                onReference = onReference,
                referenceService = service.references,
            ) {
                selectedNote = null
                newNoteId = null
                section = note.kind
            }
        } else if (showQuestions) {
            KnowledgeQuestionHistory(service.answerHistory, { showQuestions = false }) { id ->
                selectedNote = id
                showQuestions = false
                section = "NOTE"
            }
        } else if (item == null) {
            KnowledgeLibrary(
                section = section,
                onTab = { section = it },
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
                selectionMode = selectingSources,
                onCreate = { createNote(emptyList()) },
                onQuote = { kind, id ->
                    scope.launch {
                        try {
                            val value = service.references.select(kind, id)
                            if (!onReference(listOf(value))) failed = true
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: IllegalArgumentException) {
                            failed = true
                        } catch (_: Exception) {
                            failed = true
                        }
                    }
                },
                controls = {
                    KnowledgeImportAction(
                        service,
                        { createNote(emptyList()) },
                        onKnowledge = { createNote(emptyList(), "KNOWLEDGE") },
                        onSelectSources = {
                            section = "SOURCE"
                            selectingSources = true
                        },
                    )
                },
                selectionActions = {
                    KnowledgeSelectionActions(
                        selectedSources.size,
                        {
                            selectedSources = emptySet()
                            selectingSources = false
                        },
                        { createNote(selectedSources.toList()) },
                        {
                            scope.launch {
                                val chosen = all.filter { it.id in selectedSources }
                                if (chosen.isNotEmpty()) {
                                    val references =
                                        chosen.map { source ->
                                            val text = source.extracted.ifBlank { source.rawText }
                                            service.references.select(
                                                "SOURCE",
                                                source.id,
                                                if (text.length >
                                                    1800
                                                ) {
                                                    "EXCERPT"
                                                } else {
                                                    "FULL"
                                                },
                                            )
                                        }
                                    if (!onReference(references)) failed = true
                                }
                            }
                        },
                    ) { KnowledgeDeleteSources(service, all.filter { it.id in selectedSources }) }
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            KnowledgeDetail(item, service, onReference, { selected = null }) { createNote(listOf(item.id)) }
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
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
private fun KnowledgeDetail(
    item: CollectedSource,
    service: KnowledgeService,
    onReference: suspend (List<com.helix.core.storage.repository.KnowledgeReference>) -> Boolean,
    onBack: () -> Unit,
    onDistill: () -> Unit,
) {
    if (item.state == "DELETING") {
        Text(stringResource(R.string.knowledge_deleting))
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    var noViewer by remember { mutableStateOf(false) }
    var summary by rememberSaveable(item.id) { mutableStateOf(item.summary) }
    var summaryHash by rememberSaveable(item.id) { mutableStateOf(item.documentHash) }
    var summaryFailed by remember { mutableStateOf(false) }
    val files = remember(item.filesJson) { JSONArray(item.filesJson) }

    suspend fun referenceSource() {
        try {
            val text = item.extracted.ifBlank { item.rawText }
            val reference = service.references.select("SOURCE", item.id, if (text.length > 1800) "EXCERPT" else "FULL")
            if (!onReference(listOf(reference))) openFailed = true
        } catch (
            cancel: kotlinx.coroutines.CancellationException,
        ) {
            throw cancel
        } catch (
            _: IllegalArgumentException,
        ) {
            openFailed = true
        } catch (_: Exception) {
            openFailed = true
        }
    }
    var details by rememberSaveable(item.id) { mutableStateOf(false) }
    val readable = item.extracted.ifBlank { item.rawText }
    Column(Modifier.fillMaxSize()) {
        KnowledgeHeader(
            stringResource(R.string.knowledge_sources_tab),
            "knowledge-back",
            onBack,
            onMore = { details = true },
        )
        LazyColumn(
            Modifier.weight(1f).testTag("knowledge-detail"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(item.title, style = MaterialTheme.typography.headlineMedium) }
            item { Text(knowledgeDate(item.receivedAt), style = MaterialTheme.typography.labelMedium) }
            if (item.state != "READY") item { Text(knowledgeStatus(item)) }
            if (readable.isBlank()) {
                item { Text(stringResource(R.string.knowledge_visual_reading_hint)) }
                item {
                    TextButton(onClick = { details = true }) {
                        Text(stringResource(R.string.knowledge_source_info))
                    }
                }
            } else {
                item {
                    SelectionContainer {
                        com.helix.app.ui
                            .MarkdownText(readable, Modifier.fillMaxWidth().testTag("knowledge-source-body"))
                    }
                }
            }
            if (item.detail == "import-interrupted") item { Text(stringResource(R.string.knowledge_reshare)) }
            if (item.detail != "import-interrupted" && item.state in setOf("FAILED", "INCOMPLETE")) {
                item {
                    TextButton(onClick = { service.retry(item) }, modifier = Modifier.testTag("knowledge-retry")) {
                        Text(stringResource(R.string.knowledge_retry))
                    }
                }
            }
        }
        if (openFailed) Text(stringResource(R.string.knowledge_reference_failed))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { referenceSource() } },
                enabled = readable.isNotBlank(),
                modifier = Modifier.testTag("knowledge-reference"),
                shape = KnowledgeActionShape,
            ) { Text(stringResource(R.string.knowledge_ask_content)) }
            TextButton(onDistill, enabled = readable.isNotBlank(), modifier = Modifier.testTag("knowledge-distill")) {
                Text(stringResource(R.string.knowledge_make_knowledge))
            }
        }
    }
    if (details) {
        com.helix.app.ui.ConversationSheet(
            stringResource(R.string.knowledge_source_info),
            "knowledge-source-info",
            { details = false },
            scrollContent = false,
        ) {
            LazyColumn(
                Modifier.heightIn(max = 440.dp).testTag("knowledge-source-info-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                        openFailed = false
                        noViewer = false
                        try {
                            val uri = service.originalUri(item, index)
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, metadata.getString("mime"))
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        } catch (_: android.content.ActivityNotFoundException) {
                            noViewer = true
                        } catch (_: Exception) {
                            openFailed = true
                        }
                    }) { Text(metadata.getString("name")) }
                }
                if (noViewer) item { Text(stringResource(R.string.knowledge_no_viewer)) }
                if (openFailed) item { Text(stringResource(R.string.knowledge_original_unavailable)) }
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
                                val saved = service.saveSummary(item.copy(documentHash = summaryHash), summary)
                                summaryHash = saved.documentHash
                                summaryFailed = false
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                summaryFailed = true
                            }
                        }
                    }, modifier = Modifier.testTag("knowledge-save-summary")) {
                        Text(stringResource(R.string.knowledge_save_summary))
                    }
                }
                if (summaryFailed) item { Text(stringResource(R.string.knowledge_note_save_failed)) }
                item { KnowledgeDeleteSources(service, listOf(item)) }
            }
        }
    }
}
