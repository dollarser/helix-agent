package com.helix.app.knowledge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeNoteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
// UI reports service failures without claiming an answer was produced.
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal fun KnowledgeQuestionScreen(
    service: KnowledgeQuestionService,
    providers: ProviderService?,
    onBack: () -> Unit,
    onNote: (String) -> Unit,
) {
    val answers by service.answers.collectAsStateWithLifecycle(emptyList())
    val failure by service.failure.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<CollectedSource>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var model by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chooseModel by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PreparedKnowledgeAnalysis?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val rows =
        providers
            ?.rows
            ?.collectAsStateWithLifecycle()
            ?.value
            .orEmpty()
    BackHandler { onBack() }

    fun perform(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            failed = false
            try {
                block()
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                busy =
                    false
            }
        }
    }
    if (chooseModel) {
        AlertDialog(onDismissRequest = {
            chooseModel = false
        }, title = { Text(stringResource(R.string.knowledge_choose_model)) }, text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                rows.filter { !it.managedExternally }.forEach { row ->
                    items(row.conversationModels.filter(row::modelSelectable)) { name ->
                        TextButton(onClick = {
                            model = row.id to name
                            chooseModel = false
                        }) { Text("${row.displayName} · ${row.modelLabel(name)}\n${row.origin}") }
                    }
                }
            }
        }, confirmButton = {
            TextButton(
                onClick = { chooseModel = false },
            ) { Text(stringResource(R.string.knowledge_close)) }
        })
    }
    pending?.let { request ->
        AlertDialog(onDismissRequest = {
            pending = null
        }, title = { Text(stringResource(R.string.knowledge_confirm_generation)) }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("${request.destination.providerName}\n${request.destination.origin}\n${request.model}")
                    Text(
                        stringResource(
                            if (request.destination.residence ==
                                ProviderResidence.ON_DEVICE_LOCAL
                            ) {
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
                    Text(request.note.body)
                }
                items(request.sources, key = { it.sourceId }) { source ->
                    QuestionEvidence(source)
                }
            }
        }, confirmButton = {
            TextButton(
                onClick = {
                    pending = null
                    service.ask(request)
                },
                modifier =
                    Modifier.testTag(
                        "knowledge-qa-confirm",
                    ),
            ) { Text(stringResource(R.string.knowledge_confirm_send)) }
        }, dismissButton = {
            TextButton(onClick = { pending = null }) { Text(stringResource(R.string.knowledge_keep_editing)) }
        })
    }
    LazyColumn(Modifier.fillMaxSize().testTag("knowledge-qa"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.knowledge_back)) }
            Text(stringResource(R.string.knowledge_qa_scope))
            OutlinedTextField(question, {
                question = it.take(2000)
                searched = false
                hits = emptyList()
                selected =
                    emptySet()
            }, label = {
                Text(stringResource(R.string.knowledge_qa_question))
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("knowledge-qa-question"))
            TextButton(
                onClick = {
                    perform {
                        hits = service.search(question)
                        selected = emptySet()
                        searched = true
                    }
                },
                enabled =
                    !busy && question.isNotBlank(),
                modifier = Modifier.testTag("knowledge-qa-search"),
            ) { Text(stringResource(R.string.knowledge_qa_search)) }
            if (searched && hits.isEmpty()) Text(stringResource(R.string.knowledge_qa_no_results))
            if (failed || failure != null) Text(stringResource(R.string.knowledge_qa_failed))
        }
        items(hits, key = { it.id }) { source ->
            Column {
                Checkbox(checked = source.id in selected, onCheckedChange = { checked ->
                    selected =
                        if (!checked) {
                            selected - source.id
                        } else if (selected.size <
                            8
                        ) {
                            selected + source.id
                        } else {
                            selected
                        }
                }, modifier = Modifier.testTag("knowledge-qa-select-${source.id}"))
                Text("${source.title}\n${source.sourceUrl}\n${source.state}: ${source.detail}")
            }
        }
        item {
            TextButton(onClick = { chooseModel = true }, enabled = providers != null) {
                Text(model?.let { "${it.first} · ${it.second}" } ?: stringResource(R.string.knowledge_choose_model))
            }
            TextButton(
                onClick = {
                    val target = requireNotNull(model)
                    val questionSnapshot = question
                    val ids = selected.toList()
                    perform {
                        val sources = service.evidence(questionSnapshot, ids)
                        pending =
                            service.prepare(
                                questionSnapshot,
                                sources,
                                KnowledgeAnalysis(requireNotNull(providers)),
                                target.first,
                                target.second,
                            )
                    }
                },
                enabled = !busy && model != null && selected.isNotEmpty(),
                modifier =
                    Modifier.testTag(
                        "knowledge-qa-preview",
                    ),
            ) {
                Text(stringResource(R.string.knowledge_qa_preview))
            }
        }
        items(answers, key = { it.id }) { answer ->
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("knowledge-answer-${answer.id}"),
            ) {
                Text(answer.question, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.knowledge_qa_generated))
                Text(
                    stringResource(
                        when (answer.state) {
                            "RUNNING" -> R.string.knowledge_qa_running
                            "READY" -> R.string.knowledge_qa_ready
                            "INTERRUPTED" -> R.string.knowledge_qa_interrupted
                            else -> R.string.knowledge_qa_failed
                        },
                    ),
                )
                val target = remember(answer.targetJson) { org.json.JSONObject(answer.targetJson) }
                Text("${target.optString("model")}\n${target.optString("origin")}")
                SelectionContainer {
                    com.helix.app.ui
                        .MarkdownText(answer.body)
                }
                if (answer.state ==
                    "RUNNING"
                ) {
                    TextButton(
                        onClick = { service.cancel(answer.id) },
                    ) { Text(stringResource(R.string.knowledge_qa_stop)) }
                }
                TextButton(onClick = {
                    expanded =
                        if (expanded ==
                            answer.id
                        ) {
                            null
                        } else {
                            answer.id
                        }
                }) { Text(stringResource(R.string.knowledge_sources_tab)) }
                if (expanded ==
                    answer.id
                ) {
                    KnowledgeQuestionService.decodeSources(answer.sourcesJson).forEach { source ->
                        QuestionEvidence(source)
                    }
                }
                if (answer.state ==
                    "READY"
                ) {
                    TextButton(onClick = {
                        perform {
                            onNote(service.save(answer.id))
                        }
                    }, enabled = !busy, modifier = Modifier.testTag("knowledge-answer-save-${answer.id}")) {
                        Text(stringResource(R.string.knowledge_qa_save))
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun QuestionEvidence(source: KnowledgeNoteSource) {
    val received =
        java.text.DateFormat
            .getDateTimeInstance()
            .format(java.util.Date(source.receivedAt))
    SelectionContainer {
        Text(
            "[source:${source.sourceId}] ${source.title}\n${source.url}\n$received\n" +
                "${source.completeness}\n${source.excerpt}",
        )
    }
}
