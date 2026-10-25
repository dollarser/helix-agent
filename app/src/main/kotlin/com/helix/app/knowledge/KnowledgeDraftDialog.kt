package com.helix.app.knowledge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.helix.core.storage.knowledge.KnowledgeNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Local editable synthesis; opening, previewing or cancelling never creates a stored note. */
@Composable
@Suppress("FunctionName")
internal fun KnowledgeDraftDialog(
    source: KnowledgeNote,
    service: KnowledgeNoteService,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val expectedVersion = rememberSaveable(source.id) { source.updatedAt }
    KnowledgeCandidateEditor(source.id, source.title, source.body, true, onDismiss) { title, body ->
        check(source.updatedAt == expectedVersion)
        onSaved(service.createKnowledge(source, title, body))
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun KnowledgeSourceDraftDialog(
    ids: List<String>,
    hashes: List<String>,
    sources: List<com.helix.core.storage.knowledge.CollectedSource>,
    service: KnowledgeNoteService,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val available =
        sources.size == ids.size && sources.map { knowledgeHash(it.extracted.ifBlank { it.rawText }) } == hashes
    val initial = sources.joinToString("\n\n") { it.extracted.ifBlank { it.rawText } }.take(24000)
    KnowledgeCandidateEditor(
        ids.joinToString(":"),
        sources.firstOrNull()?.title.orEmpty(),
        initial,
        available,
        onDismiss,
    ) { title, body ->
        check(available)
        onSaved(service.createFromSources(sources, title, body))
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "TooGenericExceptionCaught")
private fun KnowledgeCandidateEditor(
    key: String,
    initialTitle: String,
    initialBody: String,
    available: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (String, String) -> Unit,
) {
    var title by rememberSaveable(key) { mutableStateOf(initialTitle) }
    var body by rememberSaveable(key) { mutableStateOf(initialBody) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ConversationSheet(stringResource(R.string.knowledge_knowledge_candidate), "knowledge-candidate", {
        if (!busy) onDismiss()
    }, scrollContent = false) {
        Text(stringResource(R.string.knowledge_knowledge_candidate_scope))
        if (!available) Text(stringResource(R.string.knowledge_reference_changed))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                title,
                { title = it.take(160) },
                enabled = !busy,
                label = {
                    Text(stringResource(R.string.knowledge_note_title))
                },
                modifier = Modifier.testTag("knowledge-candidate-title"),
            )
            OutlinedTextField(
                body,
                { body = it.take(MAX_KNOWLEDGE_TEXT) },
                enabled = !busy,
                minLines = 5,
                label = { Text(stringResource(R.string.knowledge_note_body)) },
                modifier = Modifier.testTag("knowledge-candidate-body"),
            )
        }
        if (failed) Text(stringResource(R.string.knowledge_management_failed))
        TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.knowledge_close)) }
        Button(
            enabled = available && !busy && title.isNotBlank() && body.isNotBlank(),
            modifier =
                Modifier.testTag(
                    "knowledge-candidate-save",
                ),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        onSave(title, body)
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
            },
        ) { Text(stringResource(R.string.knowledge_save_knowledge)) }
    }
}
