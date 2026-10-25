package com.helix.app.knowledge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Existing answers remain readable; new questions use the normal conversation and reference picker. */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal fun KnowledgeQuestionHistory(
    service: KnowledgeAnswerHistory,
    onBack: () -> Unit,
    onNote: (String) -> Unit,
) {
    val answers by service.answers.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var evidence by rememberSaveable { mutableStateOf(false) }
    // A cancelled composition must not restore a process-local in-flight flag.
    var busy by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler { if (selected != null) selected = null else onBack() }
    val answer = answers.firstOrNull { it.id == selected }
    Column {
        KnowledgeHeader(stringResource(R.string.knowledge_related_questions), "knowledge-history-back", {
            if (selected != null) selected = null else onBack()
        })
        if (failed) Text(stringResource(R.string.knowledge_management_failed))
        LazyColumn(
            Modifier.weight(1f).testTag("knowledge-answer-history"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (answer == null) {
                if (answers.isEmpty()) item { Text(stringResource(R.string.knowledge_history_empty)) }
                items(answers, key = { it.id }) { row ->
                    TextButton(
                        onClick = { selected = row.id },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("knowledge-answer-${row.id}"),
                    ) {
                        Text(row.question)
                    }
                }
            } else {
                item {
                    Text(
                        answer.question,
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                    )
                }
                item { Text(knowledgeDate(answer.createdAt)) }
                item {
                    Text(
                        stringResource(
                            when (answer.state) {
                                "READY" -> R.string.knowledge_qa_ready
                                "RUNNING" -> R.string.knowledge_qa_running
                                "INTERRUPTED" -> R.string.knowledge_qa_interrupted
                                else -> R.string.knowledge_qa_failed
                            },
                        ),
                    )
                }
                item {
                    com.helix.app.ui
                        .MarkdownText(answer.body)
                }
                item {
                    TextButton(onClick = { evidence = !evidence }) {
                        Text(stringResource(R.string.knowledge_source_details))
                    }
                }
                if (evidence) {
                    val sources = runCatching { KnowledgeAnswerHistory.decodeSources(answer.sourcesJson) }
                    if (sources.isFailure) item { Text(stringResource(R.string.knowledge_management_failed)) }
                    items(sources.getOrDefault(emptyList()), key = { it.sourceId }) { source ->
                        Text(source.title)
                        Text(source.excerpt)
                    }
                }
                if (answer.state == "READY") {
                    item {
                        TextButton(onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    onNote(service.save(answer.id))
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
                        }, enabled = !busy, modifier = Modifier.testTag("knowledge-answer-save-${answer.id}")) {
                            Text(stringResource(R.string.knowledge_qa_save))
                        }
                    }
                }
            }
        }
    }
}
