package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.app.projects.ProjectService

internal const val PROJECT_ROUTE = "project/{projectId}"

internal fun projectRoute(id: String) = "project/$id"

@Composable
@Suppress("FunctionName")
internal fun ProjectsScreen(
    service: ProjectService,
    files: FileManagerService,
    onOpen: (String) -> Unit,
) {
    val projects by service.projects.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("screen-projects")) {
        TextButton(
            { creating = true },
            Modifier.testTag("project-create"),
        ) { Text(stringResource(R.string.project_create)) }
        OutlinedTextField(query, {
            query = it
        }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.project_search)) })
        TextButton({
            archived = !archived
        }) { Text(stringResource(if (archived) R.string.project_active else R.string.project_archived)) }
        val visible =
            projects.filter {
                (it.archivedAt != null) == archived &&
                    (it.name.contains(query, true) || it.description.contains(query, true))
            }
        IndicatedLazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (visible.isEmpty()) item { Text(stringResource(R.string.project_empty)) }
            items(visible, key = { it.id }) { project ->
                Card(
                    onClick = { onOpen(project.id) },
                    modifier = Modifier.fillMaxWidth().testTag("project-${project.id}"),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(project.name, maxLines = 2)
                        if (project.description.isNotBlank()) Text(project.description, maxLines = 2)
                    }
                }
            }
        }
    }
    if (creating) {
        ProjectEditor(service, files, null, { creating = false }) {
            creating = false
            onOpen(it)
        }
    }
}
