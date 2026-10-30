package com.helix.app.knowledge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.ConversationSheet
import com.helix.core.storage.repository.KnowledgeReference
import com.helix.core.storage.repository.KnowledgeReferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Selection is local until Add; neither reading nor checking a row submits a conversation. */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught", "ThrowsCount")
fun KnowledgeReferencePicker(
    service: KnowledgeReferenceService,
    current: List<KnowledgeReference>,
    onApply: (List<KnowledgeReference>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("ALL") }
    var selected by rememberSaveable { mutableStateOf(current.map { "${it.kind}:${it.resourceId}" }) }
    var candidates by remember { mutableStateOf(emptyList<KnowledgeReferenceService.Candidate>()) }
    var preview by remember { mutableStateOf<KnowledgeReference?>(null) }
    var preparedJson by rememberSaveable { mutableStateOf(KnowledgeReferences.encode(current)) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(service, query) {
        try {
            service.catalogChanges.collect { candidates = service.candidates(query) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    preview?.let { item ->
        KnowledgeReferencePreview(item, service, onChange = { changed ->
            val key = "${changed.kind}:${changed.resourceId}"
            val existing = KnowledgeReferences.decode(preparedJson).filterNot { "${it.kind}:${it.resourceId}" == key }
            val retained =
                if (existing.size < 8) {
                    existing
                } else {
                    existing.filter { "${it.kind}:${it.resourceId}" in selected }
                }
            if (retained.size < 8) preparedJson = KnowledgeReferences.encode(retained + changed)
            preview = changed
        }, onDismiss = { preview = null })
    }
    ConversationSheet(
        stringResource(R.string.knowledge_reference_picker),
        "knowledge-reference-picker",
        onDismiss,
        scrollContent = false,
    ) {
        OutlinedTextField(
            query,
            { query = it.take(200) },
            label = { Text(stringResource(R.string.knowledge_reference_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("knowledge-reference-search"),
        )
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) {
            listOf("ALL", "KNOWLEDGE", "NOTE", "SOURCE").forEach { kind ->
                TextButton(
                    onClick = { category = kind },
                    Modifier.testTag("knowledge-reference-category-$kind"),
                ) {
                    Text(
                        stringResource(referenceKindLabel(kind)),
                        color =
                            if (category == kind) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.knowledge_reference_failed),
                Modifier.testTag("knowledge-reference-error"),
            )
        }
        LazyColumn(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .testTag("knowledge-reference-items"),
        ) {
            items(
                candidates.filter { category == "ALL" || it.kind == category },
                key = { "${it.kind}:${it.id}" },
            ) { item ->
                val key = "${item.kind}:${item.id}"
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Checkbox(
                        key in selected,
                        { checked ->
                            if (!checked) {
                                selected = selected - key
                                preparedJson =
                                    KnowledgeReferences.encode(
                                        KnowledgeReferences
                                            .decode(preparedJson)
                                            .filterNot { "${it.kind}:${it.resourceId}" == key },
                                    )
                            } else if (selected.size < 8) {
                                busy = true
                                scope.launch {
                                    try {
                                        val old = KnowledgeReferences.decode(preparedJson)
                                        val snapshot =
                                            old.firstOrNull { "${it.kind}:${it.resourceId}" == key }
                                                ?: service.select(item.kind, item.id)
                                        preparedJson =
                                            KnowledgeReferences.encode(
                                                old.filterNot {
                                                    "${it.kind}:${it.resourceId}" == key
                                                } + snapshot,
                                            )
                                        selected = selected + key
                                    } catch (
                                        cancel: CancellationException,
                                    ) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        failed = true
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy && (item.available || key in selected),
                        modifier = Modifier.testTag("knowledge-reference-select-${item.id}"),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                if (item.available) {
                                    R.string.knowledge_reference_characters
                                } else {
                                    R.string.knowledge_reference_unavailable
                                },
                                item.characters,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        enabled = item.available && !busy,
                        modifier = Modifier.testTag("knowledge-reference-preview-${item.id}"),
                        onClick = {
                            scope.launch {
                                try {
                                    preview =
                                        KnowledgeReferences
                                            .decode(preparedJson)
                                            .firstOrNull { it.kind == item.kind && it.resourceId == item.id }
                                            ?: service.select(
                                                item.kind,
                                                item.id,
                                            )
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    failed = true
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.knowledge_reference_preview)) }
                }
            }
        }
        Text(
            stringResource(R.string.knowledge_reference_add_scope),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.knowledge_close)) }
            TextButton(
                enabled = (selected.isNotEmpty() || current.isNotEmpty()) && !busy,
                modifier = Modifier.testTag("knowledge-reference-apply"),
                onClick = {
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            val items =
                                selected.map { key ->
                                    KnowledgeReferences
                                        .decode(preparedJson)
                                        .firstOrNull { "${it.kind}:${it.resourceId}" == key }
                                        ?: candidates.first { "${it.kind}:${it.id}" == key }.let {
                                            service.select(
                                                it.kind,
                                                it.id,
                                            )
                                        }
                                }
                            KnowledgeReferences.encode(items)
                            check(service.valid(items))
                            onApply(items)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.knowledge_reference_add_selected, selected.size)) }
        }
    }
}

internal fun referenceKindLabel(kind: String): Int =
    when (kind) {
        "NOTE" -> R.string.knowledge_library_notes
        "KNOWLEDGE" -> R.string.knowledge_library_knowledge
        "SOURCE" -> R.string.knowledge_library_sources
        else -> R.string.knowledge_reference_all
    }

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
fun KnowledgeReferencePreview(
    reference: KnowledgeReference,
    service: KnowledgeReferenceService? = null,
    onChange: ((KnowledgeReference) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var state by remember(reference) { mutableStateOf(KnowledgeReferenceService.State.CURRENT) }
    var body by remember(reference) { mutableStateOf<String?>(null) }
    var offset by remember(reference) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    LaunchedEffect(reference, service) {
        if (service != null) {
            try {
                state = service.state(reference)
                body = service.preview(reference)
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                state = KnowledgeReferenceService.State.UNAVAILABLE
            }
        }
    }
    ConversationSheet(reference.title, "knowledge-reference-preview", onDismiss) {
        Text(stringResource(R.string.knowledge_reference_lazy, reference.characters))
        if (reference.completeness !in setOf("USER_CONTENT", "READY")) {
            Text(stringResource(R.string.knowledge_reference_partial))
        }
        if (state != KnowledgeReferenceService.State.CURRENT) {
            Text(stringResource(R.string.knowledge_reference_changed))
            if (onChange != null && service != null) {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            onChange(service.select(reference.kind, reference.resourceId))
                        } catch (
                            cancel: CancellationException,
                        ) {
                            throw cancel
                        } catch (_: Exception) {
                            state = KnowledgeReferenceService.State.UNAVAILABLE
                        }
                    }
                }, modifier = Modifier.testTag("knowledge-reference-refresh")) {
                    Text(stringResource(R.string.knowledge_reference_refresh))
                }
            }
        }
        body?.let { content ->
            val total = content.codePointCount(0, content.length)
            val end = minOf(total, offset + 2048)
            SelectionContainer {
                Text(
                    content.substring(
                        content.offsetByCodePoints(0, offset),
                        content.offsetByCodePoints(0, end),
                    ),
                )
            }
            Row {
                TextButton(onClick = { offset = (offset - 2048).coerceAtLeast(0) }, enabled = offset > 0) {
                    Text(stringResource(R.string.knowledge_reference_previous))
                }
                TextButton(onClick = { offset = end }, enabled = end < total) {
                    Text(stringResource(R.string.knowledge_reference_next))
                }
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            androidx.compose.ui.platform.ClipEntry(
                                android.content.ClipData.newPlainText(reference.title, content),
                            ),
                        )
                    }
                }) { Text(stringResource(R.string.knowledge_reference_copy)) }
            }
        }
        Text(stringResource(R.string.knowledge_reference_send_scope), style = MaterialTheme.typography.bodySmall)
    }
}
