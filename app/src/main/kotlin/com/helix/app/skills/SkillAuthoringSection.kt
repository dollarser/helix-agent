package com.helix.app.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.rememberImportActionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName", "LongMethod") // Native form and preview remain one screen; action handling is shared.
fun SkillAuthoringSection(service: SkillAuthoringService) {
    var opened by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf<String?>(null) }
    var expectedHash by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    val actions = rememberImportActionState()

    fun action(block: suspend () -> Unit) {
        actions.launch(onFailure = { failure ->
            diagnostic = (failure as? com.helix.extensions.skills.InvalidSkillException)?.message?.take(512)
        }) {
            diagnostic = null
            result = ""
            block()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { opened = !opened }, modifier = Modifier.testTag("skill-creator-open")) {
            Text(stringResource(R.string.skill_creator_title))
        }
        if (opened) {
            Text(stringResource(R.string.skill_creator_intro))
            if (manifest == null) {
                OutlinedTextField(
                    name,
                    {
                        name = it
                        expectedHash = null
                    },
                    label = { Text(stringResource(R.string.skill_creator_name)) },
                    modifier = Modifier.testTag("skill-creator-name"),
                    enabled = !actions.busy,
                )
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text(stringResource(R.string.skill_creator_description)) },
                    modifier = Modifier.testTag("skill-creator-description"),
                    enabled = !actions.busy,
                )
                OutlinedTextField(
                    body,
                    { body = it },
                    label = { Text(stringResource(R.string.skill_creator_body)) },
                    modifier = Modifier.testTag("skill-creator-body"),
                    enabled = !actions.busy,
                    minLines = 3,
                )
            } else {
                OutlinedTextField(
                    manifest.orEmpty(),
                    { manifest = it },
                    label = { Text("SKILL.md") },
                    enabled = !actions.busy,
                    minLines = 6,
                    modifier = Modifier.testTag("skill-creator-manifest"),
                )
            }
            OutlinedButton(modifier = Modifier.testTag("skill-creator-save"), enabled = !actions.busy, onClick = {
                action {
                    path =
                        withContext(Dispatchers.IO) {
                            val current = manifest
                            if (current == null) {
                                service.saveDraft(name, description, body)
                            } else {
                                service.saveEditedDraft(path, current, requireNotNull(expectedHash))
                            }
                        }
                    val saved = withContext(Dispatchers.IO) { service.loadDraft(path) }
                    expectedHash = saved.contentHash
                    manifest = saved.manifest
                    result = path
                }
            }) { Text(stringResource(R.string.skill_creator_save)) }
            OutlinedTextField(
                path,
                {
                    path = it
                    manifest = null
                    expectedHash = null
                },
                label = { Text(stringResource(R.string.skill_creator_path)) },
                enabled = !actions.busy,
            )
            OutlinedButton(enabled = !actions.busy && path.startsWith("scope:app:work/skills/"), onClick = {
                action {
                    val draft = withContext(Dispatchers.IO) { service.loadDraft(path) }
                    name = draft.name
                    description = draft.description
                    body = draft.body
                    expectedHash = draft.contentHash
                    manifest = draft.manifest
                }
            }) { Text(stringResource(R.string.skill_creator_load)) }
            OutlinedButton(
                enabled = !actions.busy && path.isNotBlank(),
                onClick = {
                    action {
                        val preview = withContext(Dispatchers.IO) { service.preview(path) }
                        result = "${preview.name}\n${preview.description}\n${preview.snapshotHash}\n" +
                            listOfNotNull(
                                preview.compatibility,
                                preview.declaredAllowedTools,
                            ).joinToString("\n") + "\n" +
                            preview.files.joinToString("\n") { "${it.relativePath} · ${it.sizeBytes} B" }
                    }
                },
                modifier =
                    Modifier.testTag(
                        "skill-creator-preview",
                    ),
            ) { Text(stringResource(R.string.skill_creator_preview)) }
            if (actions.failed) {
                Text(stringResource(R.string.skill_creator_failed))
                diagnostic?.let { Text("SKILL.md: $it") }
            }
            if (result.isNotEmpty()) Text(result, modifier = Modifier.testTag("skill-creator-result"))
        }
    }
}
