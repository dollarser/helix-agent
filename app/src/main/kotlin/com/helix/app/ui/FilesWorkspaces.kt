package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Session directories are an auxiliary collection, never additional home storage devices. */
@Composable
@Suppress("FunctionName")
internal fun FilesWorkspaces(state: FilesScreenState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).testTag("files-workspaces"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton({
            state.goHome()
        }, modifier = Modifier.testTag("files-home-open")) { Text(stringResource(R.string.files_home)) }
        Text(stringResource(R.string.files_session_workspaces), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            state.workspaceQuery,
            { state.workspaceQuery = it },
            label = { Text(stringResource(R.string.files_workspace_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("files-workspace-search"),
        )
        val matches =
            state.workspaces.filter {
                it.displayName.contains(state.workspaceQuery, ignoreCase = true) ||
                    it.scopeId.contains(state.workspaceQuery, ignoreCase = true)
            }
        if (matches.isEmpty()) Text(stringResource(R.string.files_workspaces_empty))
        IndicatedLazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(matches, key = { it.scopeId }) { source ->
                FileLocationCard(
                    source.displayName,
                    source.scopeId + " · " +
                        stringResource(
                            if (source.available) R.string.files_workspace_files else R.string.workspace_unavailable,
                        ),
                    "files-workspace-${source.scopeId}",
                ) { state.openLocation(source.scopeId) }
            }
        }
    }
}
