package com.helix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.indicatedVerticalScroll

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun FilesHome(
    state: FilesScreenState,
    actions: FilesScreenActions,
    onPermissions: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-files"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.files_home_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FileLibraryHome(state, actions)
        Text(stringResource(R.string.files_quick_access), style = MaterialTheme.typography.titleMedium)
        listOf(
            "Download" to R.string.files_downloads_folder,
            "Documents" to R.string.files_documents_folder,
            "DCIM" to R.string.files_camera_folder,
            "Pictures" to R.string.files_pictures_folder,
            "Movies" to R.string.files_movies_folder,
            "Music" to R.string.files_music_folder,
        ).chunked(2).forEach { folders ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { (path, label) ->
                    Card(
                        onClick = { onPermissions(path) },
                        modifier = Modifier.weight(1f).testTag("files-quick-$path"),
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_files_folder), null)
                            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.files_locations), style = MaterialTheme.typography.titleMedium)
        FileLocationCard(
            stringResource(R.string.files_shared),
            stringResource(R.string.files_shared_detail),
            "files-shared-open",
        ) { onPermissions("") }
        state.locations.forEach { source ->
            if (source.scopeId == com.helix.app.files.SharedStorageAccess.SCOPE_ID) return@forEach
            FileLocationCard(
                if (source.scopeId == state.localScopeId) stringResource(R.string.files_local) else source.displayName,
                stringResource(
                    when {
                        !source.available -> R.string.workspace_unavailable
                        source.scopeId == state.localScopeId -> R.string.files_local_detail
                        source.supportsMutation -> R.string.files_location_editable
                        else -> R.string.files_location_readonly
                    },
                ),
                "files-home-source-${source.scopeId}",
            ) { state.openLocation(source.scopeId) }
        }
        TextButton({ state.safPanelOpen = true }, modifier = Modifier.testTag("files-saf-open")) {
            Text(stringResource(R.string.files_add_location))
        }
        Text(stringResource(R.string.files_helix_tools), style = MaterialTheme.typography.titleMedium)
        FileLocationCard(
            stringResource(R.string.files_session_workspaces),
            stringResource(R.string.files_session_workspaces_detail),
            "files-workspaces-open",
        ) { state.openWorkspaces() }
        state.status?.let { Text(it, modifier = Modifier.testTag("files-status")) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.files_access), style = MaterialTheme.typography.titleMedium)
                TextButton({
                    state.importResult = null
                    state.importOpen = true
                }, modifier = Modifier.testTag("files-import-open")) { Text(actions.str(R.string.files_import_button)) }
                if (actions.fileManager.isRootSupported && !actions.fileManager.isRootGranted) {
                    TextButton(
                        onClick = { actions.requestRoot() },
                        modifier = Modifier.testTag("files-root-request"),
                    ) {
                        Text(stringResource(R.string.files_request_root))
                    }
                }
                FilesRecoveryPanel(actions)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun FileLocationCard(
    title: String,
    detail: String,
    tag: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_files_folder),
                null,
                Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}
