package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.plugin.ExpertAbilityChoice
import com.helix.core.storage.repository.ExpertDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Full-height draft editor; only Save publishes a reusable definition. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList", "TooGenericExceptionCaught")
internal fun ExpertEditor(
    id: String,
    initial: ExpertDefinition?,
    importing: Boolean,
    chat: ChatService,
    pluginChoices: List<ExpertAbilityChoice>,
    skillChoices: List<ExpertAbilityChoice>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by rememberSaveable(id) { mutableStateOf(initial?.profile?.displayName.orEmpty()) }
    var description by rememberSaveable(id) { mutableStateOf(initial?.description.orEmpty()) }
    var instruction by rememberSaveable(id) { mutableStateOf(initial?.profile?.instruction.orEmpty()) }
    var plugins by rememberSaveable(id) { mutableStateOf(initial?.profile?.recommendedConnectorIds.orEmpty()) }
    var skills by rememberSaveable(id) { mutableStateOf(initial?.profile?.recommendedSkillIds.orEmpty()) }
    var imported by rememberSaveable(id) { mutableStateOf("") }
    var busy by remember(id) { mutableStateOf(false) }
    var failed by rememberSaveable(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding().testTag("expert-editor"),
            topBar = {
                Text(
                    stringResource(if (importing) R.string.expert_import else R.string.expert_library),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            bottomBar = {
                FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = !busy,
                        onClick = onDismiss,
                        modifier = Modifier.testTag("expert-editor-cancel"),
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(enabled = !busy, onClick = {
                        busy = true
                        failed = false
                        scope.launch {
                            try {
                                if (importing) {
                                    check(chat.importExpert(imported, id).await())
                                } else {
                                    check(chat.saveExpert(id, name, instruction, description, plugins, skills).await())
                                }
                                onSaved()
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
                    }, modifier = Modifier.testTag("session-expert-save")) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (failed) {
                    Text(stringResource(R.string.session_expert_save_failed), color = MaterialTheme.colorScheme.error)
                }
                if (importing) {
                    Text(stringResource(R.string.expert_import_description))
                    OutlinedTextField(
                        imported,
                        { imported = it },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.expert_import_json)) },
                        minLines = 12,
                        modifier = Modifier.fillMaxWidth().testTag("expert-import-json"),
                    )
                } else {
                    OutlinedTextField(
                        name,
                        { name = it },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text(stringResource(R.string.session_expert_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("session-expert-name"),
                    )
                    OutlinedTextField(
                        description,
                        { description = it },
                        enabled = !busy,
                        minLines = 2,
                        maxLines = 3,
                        label = { Text(stringResource(R.string.expert_description)) },
                        modifier = Modifier.fillMaxWidth().testTag("expert-description"),
                    )
                    OutlinedTextField(
                        instruction,
                        { instruction = it },
                        enabled = !busy,
                        minLines = 7,
                        maxLines = 12,
                        label = { Text(stringResource(R.string.session_expert_instruction)) },
                        modifier = Modifier.fillMaxWidth().testTag("session-expert-instruction"),
                    )
                    Text(stringResource(R.string.expert_dependencies_hint), style = MaterialTheme.typography.bodySmall)
                    ExpertDependenciesEditor(
                        R.string.expert_linked_plugins,
                        pluginChoices,
                        plugins,
                        !busy,
                    ) { plugins = it }
                    ExpertDependenciesEditor(R.string.expert_linked_skills, skillChoices, skills, !busy) { skills = it }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ExpertDependenciesEditor(
    title: Int,
    choices: List<ExpertAbilityChoice>,
    selected: List<String>,
    enabled: Boolean,
    onChange: (List<String>) -> Unit,
) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    val missing =
        selected
            .filter { ref -> choices.none { it.reference == ref } }
            .map { ExpertAbilityChoice(it, it.take(80), false) }
    if (choices.isEmpty() && missing.isEmpty()) Text(stringResource(R.string.expert_no_dependencies))
    (choices + missing).forEach { choice ->
        Row(Modifier.fillMaxWidth()) {
            Checkbox(choice.reference in selected, enabled = enabled, onCheckedChange = { checked ->
                onChange(if (checked) selected + choice.reference else selected - choice.reference)
            }, modifier = Modifier.testTag("expert-dependency-${choice.reference}"))
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(choice.name)
                if (!choice.available) {
                    Text(
                        stringResource(R.string.expert_dependency_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
