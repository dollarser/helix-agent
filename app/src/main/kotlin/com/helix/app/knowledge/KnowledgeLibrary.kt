package com.helix.app.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.HelixCard
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeNote

/** Library presentation only. Selection, permissions and mutations remain with the existing owner. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod")
internal fun KnowledgeLibrary(
    notesTab: Boolean,
    onTab: (Boolean) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    val searchLabel = if (notesTab) R.string.knowledge_search_notes else R.string.knowledge_search_sources
    val empty = if (notesTab) notes.isEmpty() else sources.isEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryTabRow(selectedTabIndex = if (notesTab) 1 else 0) {
            Tab(
                !notesTab,
                { onTab(false) },
                text = { Text(stringResource(R.string.knowledge_library_sources)) },
                modifier = Modifier.testTag("knowledge-tab-sources"),
            )
            Tab(
                notesTab,
                { onTab(true) },
                text = { Text(stringResource(R.string.knowledge_library_notes)) },
                modifier = Modifier.testTag("knowledge-tab-notes"),
            )
            Tab(
                false,
                onQuestions,
                text = { Text(stringResource(R.string.knowledge_qa_tab)) },
                modifier = Modifier.testTag("knowledge-tab-questions"),
            )
        }
        OutlinedTextField(
            query,
            onQuery,
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_files_search), contentDescription = null) },
            label = {
                Text(stringResource(searchLabel))
            },
            modifier = Modifier.fillMaxWidth().testTag("knowledge-search"),
        )
        controls()
        if (!notesTab && selected.isNotEmpty()) selectionActions()
        LazyColumn(
            Modifier.weight(1f).testTag(if (notesTab) "knowledge-notes-list" else "knowledge-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (empty) {
                item(key = "empty") {
                    Text(
                        stringResource(
                            when {
                                query.isNotBlank() -> R.string.knowledge_no_search_results
                                notesTab -> R.string.knowledge_notes_empty
                                else -> R.string.knowledge_empty
                            },
                        ),
                        Modifier.padding(vertical = 20.dp).testTag("knowledge-library-empty"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (notesTab) {
                items(notes, key = { it.id }) { note -> KnowledgeNoteRow(note) { onNote(note.id) } }
            } else {
                items(sources, key = { it.id }) { source ->
                    KnowledgeSourceRow(
                        source,
                        source.id in selected,
                        { onSelect(source.id, it) },
                    ) { onSource(source.id) }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun KnowledgeNoteRow(
    note: KnowledgeNote,
    open: () -> Unit,
) {
    HelixCard(open, Modifier.fillMaxWidth().testTag("knowledge-note-${note.id}")) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(R.drawable.ic_files_document),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.body.isNotBlank()) {
                    Text(
                        note.body.take(240),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(
                        if (note.state ==
                            "REVIEWED"
                        ) {
                            R.string.knowledge_note_reviewed
                        } else {
                            R.string.knowledge_note_draft
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun KnowledgeSourceRow(
    source: CollectedSource,
    selected: Boolean,
    select: (Boolean) -> Unit,
    open: () -> Unit,
) {
    HelixCard(open, Modifier.fillMaxWidth().testTag("knowledge-item-${source.id}")) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                selected,
                select,
                enabled = source.state != "DELETING",
                modifier = Modifier.testTag("knowledge-select-${source.id}"),
            )
            Column(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    source.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    knowledgeStatus(source),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    source.sourceUrl.ifBlank { source.source },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
