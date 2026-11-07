package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.ui.shared.indicatedHorizontalScroll
import com.helix.ui.shared.R as SharedR

@Composable
@Suppress("FunctionName")
internal fun FilesLocationBar(
    state: FilesScreenState,
    actions: FilesScreenActions,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FileIconButton(R.drawable.ic_files_home, R.string.files_home, "files-home-open") { state.goHome() }
            Box(Modifier.weight(1f)) {
                TextButton({ state.sourcesOpen = true }, modifier = Modifier.testTag("files-location-picker")) {
                    Text(state.currentSource.displayName + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilesLocationMenu(state)
            }
            FileIconButton(SharedR.drawable.ic_files_search, R.string.files_search, "files-search-open") {
                state.searchOpen = !state.searchOpen
                state.searchQuery = ""
            }
            FileIconButton(SharedR.drawable.ic_chat_more, R.string.files_controls, "files-controls-open") {
                state.controlsOpen =
                    true
            }
        }
        if (state.searchOpen) {
            OutlinedTextField(
                state.searchQuery,
                { state.searchQuery = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.files_search)) },
                modifier = Modifier.fillMaxWidth().testTag("files-search-query"),
            )
        }
        Row(
            Modifier.indicatedHorizontalScroll(rememberScrollState()).testTag("files-breadcrumb"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BreadcrumbCrumb(stringResource(R.string.files_root_directory), true) { state.currentPath = "" }
            val parts = state.currentPath.split('/').filter { it.isNotEmpty() }
            parts.forEachIndexed { index, part ->
                BreadcrumbCrumb(part, false) { state.currentPath = parts.take(index + 1).joinToString("/") }
            }
        }
        Text(
            stringResource(R.string.files_current_source, state.currentSource.displayName) + " · " +
                stringResource(R.string.files_directory_count, state.visibleEntries.size) +
                if (state.canMutate) "" else stringResource(R.string.files_read_only_suffix),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("files-source-current"),
        )
    }
    if (state.controlsOpen) FilesOptionsDialog(state, actions)
}

@Composable
@Suppress("FunctionName")
private fun FilesLocationMenu(state: FilesScreenState) {
    DropdownMenu(state.sourcesOpen, { state.sourcesOpen = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_session_workspaces)) },
            onClick = { state.openWorkspaces() },
            modifier = Modifier.testTag("files-workspaces-picker"),
        )
        state.locations.forEach { source ->
            DropdownMenuItem(
                text = { Text(source.displayName) },
                onClick = { state.openLocation(source.scopeId) },
                modifier = Modifier.testTag("files-source-${source.scopeId}"),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun FileIconButton(
    icon: Int,
    label: Int,
    tag: String,
    action: () -> Unit,
) {
    IconButton(action, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(painterResource(icon), stringResource(label))
    }
}
