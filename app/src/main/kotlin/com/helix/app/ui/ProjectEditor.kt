package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.projects.ProjectService
import com.helix.core.storage.entity.ProjectEntity
import com.helix.core.workspace.FileScopePath

@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
internal fun ProjectEditor(
    service: ProjectService,
    files: FileManagerService,
    project: ProjectEntity?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(project?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(project?.description.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(project?.instructions.orEmpty()) }
    var directory by rememberSaveable {
        mutableStateOf(project?.let { FileScopePath(it.workspaceId, it.relativePath).toModelReference() })
    }
    var selecting by remember { mutableStateOf(false) }
    val actions = rememberProjectActions()
    AlertDialog(
        onDismissRequest = { if (!actions.busy) onDismiss() },
        title = { Text(stringResource(if (project == null) R.string.project_create else R.string.project_settings)) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).indicatedVerticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, {
                    name = it
                }, label = {
                    Text(stringResource(R.string.project_name))
                }, singleLine = true, modifier = Modifier.testTag("project-name"))
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text(stringResource(R.string.project_description)) },
                    maxLines = 4,
                )
                OutlinedTextField(instructions, {
                    instructions = it
                }, label = {
                    Text(
                        stringResource(R.string.project_instructions),
                    )
                }, modifier = Modifier.testTag("project-instructions"), maxLines = 6)
                Text(stringResource(R.string.project_instructions_hint))
                val selectedPath = directory?.let { FileScopePath.fromModelReference(it).relativePath }
                Text(
                    selectedPath?.takeIf { it.isNotBlank() }
                        ?: stringResource(
                            if (directory == null) {
                                R.string.project_managed_directory
                            } else {
                                R.string.project_directory_selected
                            },
                        ),
                )
                TextButton(
                    { selecting = true },
                    enabled = !actions.busy,
                ) { Text(stringResource(R.string.chat_directory_choose)) }
                Text(stringResource(R.string.project_directory_hint))
                actions.error?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(
                { actions.run { onSaved(service.save(project, name, description, instructions, directory)) } },
                enabled =
                    !actions.busy && name.isNotBlank() && name.length <= 120 &&
                        description.length <= 2_000 && instructions.length <= 16_000,
                modifier = Modifier.testTag("project-save"),
            ) { Text(stringResource(R.string.chat_save_details)) }
        },
        dismissButton = {
            TextButton(
                onDismiss,
                enabled = !actions.busy,
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    )
    if (selecting) {
        SessionDirectoryDialog(files, { selecting = false }, allowIndependent = false) { reference ->
            directory = reference
            true
        }
    }
}
