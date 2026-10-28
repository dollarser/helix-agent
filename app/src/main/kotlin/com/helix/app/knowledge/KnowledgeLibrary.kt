package com.helix.app.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeNote

/** Library presentation only. Selection, permissions and mutations remain with the existing owner. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod", "CyclomaticComplexMethod") // Category presentation only.
internal fun KnowledgeLibrary(
    section: String,
    onTab: (String) -> Unit,
    onQuestions: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    sources: List<CollectedSource>,
    notes: List<KnowledgeNote>,
    selected: Set<String>,
    onSelect: (String, Boolean) -> Unit,
    onSource: (String) -> Unit,
    onNote: (String) -> Unit,
    controls: @Composable () -> Unit,
    selectionActions: @Composable () -> Unit,
    selectionMode: Boolean,
    onQuote: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(query, section) { listState.scrollToItem(0) }
    val clearSearch = {
        focus.clearFocus()
        onQuery("")
    }
    val notesTab = section != "SOURCE"
    val visibleNotes = notes.filter { it.kind == section }
    val empty = if (notesTab) visibleNotes.isEmpty() else sources.isEmpty()
    Column(modifier) {
        OutlinedTextField(
            query,
            onQuery,
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_files_search), contentDescription = null) },
            placeholder = { Text(stringResource(R.string.knowledge_search_all)) },
            shape = KnowledgeActionShape,
            trailingIcon =
                if (query.isNotEmpty()) {
                    {
                        IconButton(clearSearch, Modifier.testTag("knowledge-clear-search")) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                stringResource(R.string.knowledge_clear_search),
                            )
                        }
                    }
                } else {
                    null
                },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("knowledge-search"),
        )
        PrimaryTabRow(
            selectedTabIndex = listOf("KNOWLEDGE", "NOTE", "SOURCE").indexOf(section),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            indicator = {
                androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(
                        listOf("KNOWLEDGE", "NOTE", "SOURCE").indexOf(section),
                        matchContentSize = false,
                    ),
                )
            },
        ) {
            for (kind in listOf("KNOWLEDGE", "NOTE", "SOURCE")) {
                Tab(
                    section == kind,
                    {
                        focus.clearFocus()
                        onTab(kind)
                    },
                    text = { Text(stringResource(referenceKindLabel(kind)), maxLines = 1, softWrap = false) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.testTag(
                            when (kind) {
                                "KNOWLEDGE" -> "knowledge-tab-knowledge"
                                "NOTE" -> "knowledge-tab-notes"
                                else -> "knowledge-tab-sources"
                            },
                        ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.knowledge_recent_count, if (notesTab) visibleNotes.size else sources.size),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onQuestions, modifier = Modifier.testTag("knowledge-tab-questions")) {
                Text(stringResource(R.string.knowledge_related_questions), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!notesTab && selectionMode) selectionActions()
        LazyColumn(
            Modifier.weight(1f).testTag(if (notesTab) "knowledge-notes-list" else "knowledge-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = listState,
        ) {
            if (empty) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp).testTag("knowledge-library-empty"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        KnowledgeLibraryIcon(section)
                        Text(
                            stringResource(
                                if (query.isNotBlank()) {
                                    R.string.knowledge_no_search_results
                                } else {
                                    when (section) {
                                        "KNOWLEDGE" -> R.string.knowledge_empty_knowledge
                                        "SOURCE" -> R.string.knowledge_empty_sources
                                        else -> R.string.knowledge_empty_notes
                                    }
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                if (query.isNotBlank()) {
                                    R.string.knowledge_empty_search_hint
                                } else {
                                    when (section) {
                                        "KNOWLEDGE" -> R.string.knowledge_empty_knowledge_hint
                                        "SOURCE" -> R.string.knowledge_empty_sources_hint
                                        else -> R.string.knowledge_empty_notes_hint
                                    }
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            if (notesTab) {
                items(visibleNotes, key = { it.id }) { note ->
                    KnowledgeNoteRow(note, { onQuote(note.kind, note.id) }) { onNote(note.id) }
                }
            } else {
                items(sources, key = { it.id }) { source ->
                    KnowledgeSourceRow(
                        source,
                        source.id in selected,
                        selectionMode,
                        { onSelect(source.id, it) },
                        { onQuote("SOURCE", source.id) },
                    ) { onSource(source.id) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) { controls() }
    }
}

@Composable
@Suppress("FunctionName")
private fun KnowledgeLibraryIcon(kind: String) {
    Surface(
        shape = KnowledgeActionShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (kind == "KNOWLEDGE") R.drawable.ic_drawer_book else R.drawable.ic_files_document),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun KnowledgeNoteRow(
    note: KnowledgeNote,
    quote: () -> Unit,
    open: () -> Unit,
) {
    Surface(
        onClick = open,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("knowledge-note-${note.id}"),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KnowledgeLibraryIcon(note.kind)
            KnowledgeNoteSummary(note, Modifier.weight(1f))
            IconButton(
                quote,
                enabled = note.body.isNotBlank(),
                modifier = Modifier.testTag("knowledge-quote-${note.id}"),
            ) {
                Icon(
                    painterResource(R.drawable.ic_knowledge_reference),
                    stringResource(R.string.knowledge_quote_action),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun KnowledgeSourceRow(
    source: CollectedSource,
    selected: Boolean,
    selectionMode: Boolean,
    select: (Boolean) -> Unit,
    quote: () -> Unit,
    open: () -> Unit,
) {
    Surface(
        onClick = open,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("knowledge-item-${source.id}"),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                Checkbox(
                    selected,
                    select,
                    enabled = source.state != "DELETING",
                    modifier = Modifier.testTag("knowledge-select-${source.id}"),
                )
            } else {
                KnowledgeLibraryIcon("SOURCE")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    source.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    source.extracted.ifBlank { source.rawText }.take(180),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    knowledgeDate(source.receivedAt) + " · " + knowledgeStatus(source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                quote,
                enabled =
                    source.state != "DELETING" &&
                        source.extracted.ifBlank { source.rawText }.isNotBlank(),
                modifier = Modifier.testTag("knowledge-quote-${source.id}"),
            ) {
                Icon(
                    painterResource(R.drawable.ic_knowledge_reference),
                    stringResource(R.string.knowledge_quote_action),
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
@Suppress("FunctionName")
internal fun KnowledgeSelectionActions(
    count: Int,
    clear: () -> Unit,
    synthesize: () -> Unit,
    question: () -> Unit,
    deletion: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.TextButton(clear, modifier = Modifier.testTag("knowledge-clear-selection")) {
            Text(stringResource(R.string.knowledge_clear_selection, count))
        }
        androidx.compose.material3.TextButton(synthesize, modifier = Modifier.testTag("knowledge-synthesize")) {
            Text(stringResource(R.string.knowledge_synthesize, count))
        }
        androidx.compose.material3.TextButton(question, modifier = Modifier.testTag("knowledge-selected-question")) {
            Text(stringResource(R.string.knowledge_selected_question))
        }
        deletion()
    }
}

@Composable
@Suppress("FunctionName")
private fun KnowledgeNoteSummary(
    note: KnowledgeNote,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            note.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (note.body.isNotBlank()) {
            Text(
                note.body.take(180),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            knowledgeDate(note.updatedAt) + " · " +
                stringResource(
                    if (note.kind == "NOTE") {
                        R.string.knowledge_note_saved
                    } else if (note.state == "REVIEWED") {
                        R.string.knowledge_note_reviewed
                    } else {
                        R.string.knowledge_note_unreviewed
                    },
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
