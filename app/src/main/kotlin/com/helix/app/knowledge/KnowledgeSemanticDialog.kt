package com.helix.app.knowledge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun KnowledgeSemanticDialog(
    service: KnowledgeService,
    onDismiss: () -> Unit,
) {
    val semantic = service.semantic
    val sources by service.items.collectAsStateWithLifecycle()
    val notes by service.noteService.notes.collectAsStateWithLifecycle(initialValue = null)
    val initial = remember { semantic.configuration() }
    var model by remember { mutableStateOf(initial.model) }
    var queryPrefix by remember { mutableStateOf(initial.queryPrefix) }
    var documentPrefix by remember { mutableStateOf(initial.documentPrefix) }
    var revision by remember { mutableStateOf(initial.modelRevision) }
    var selected by remember { mutableStateOf(initial.keys) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(semantic) {
        try {
            models = semantic.models(force = true)
            if (model !in models) model = models.firstOrNull().orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            failed = true
        } catch (_: IllegalStateException) {
            failed = true
        } finally {
            loading = false
        }
    }
    val candidates =
        sources.filter { it.state !in setOf("DELETING", "IMPORTING") }.map { "source:${it.id}" to it.title } +
            notes.orEmpty().map { "note:${it.id}" to it.title }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.semantic_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.semantic_disclosure))
                SemanticModelOptions(loading, failed, models, model) { model = it }
                Text(stringResource(R.string.semantic_selection, selected.size))
                SemanticSourceOptions(candidates, selected) { selected = it }
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.semantic_parameters)) }
                if (advanced) {
                    OutlinedTextField(queryPrefix, {
                        queryPrefix = it.take(512)
                    }, label = { Text(stringResource(R.string.semantic_query_prefix)) })
                    OutlinedTextField(documentPrefix, {
                        documentPrefix = it.take(512)
                    }, label = { Text(stringResource(R.string.semantic_document_prefix)) })
                    OutlinedTextField(revision, {
                        revision = it.take(200)
                    }, label = { Text(stringResource(R.string.semantic_model_revision)) })
                    Text(stringResource(R.string.semantic_contract_hint))
                }
                if (failed) Text(stringResource(R.string.semantic_error))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && model in models && selected.size in 1..64, onClick = {
                busy = true
                scope.launch {
                    try {
                        semantic.apply(SemanticConfiguration(model, queryPrefix, documentPrefix, revision, selected))
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: java.io.IOException) {
                        failed = true
                    } catch (_: IllegalArgumentException) {
                        failed = true
                    } catch (_: IllegalStateException) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.semantic_enable)) }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
            ) { Text(stringResource(R.string.knowledge_close)) }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun SemanticModelOptions(
    loading: Boolean,
    failed: Boolean,
    models: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    when {
        loading -> {
            Text(stringResource(R.string.semantic_loading))
        }

        failed || models.isEmpty() -> {
            Text(stringResource(R.string.semantic_unavailable))
        }

        else -> {
            models.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = selected == id, onClick = { onSelect(id) })
                    Text(id)
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SemanticSourceOptions(
    candidates: List<Pair<String, String>>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
        items(candidates, key = { it.first }) { (key, title) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = key in selected, onCheckedChange = { checked ->
                    onSelect(if (checked) selected + key else selected - key)
                })
                Text(title, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
    if (selected.isNotEmpty()) {
        TextButton(onClick = { onSelect(emptySet()) }) {
            Text(stringResource(R.string.semantic_clear_selection))
        }
    }
}
