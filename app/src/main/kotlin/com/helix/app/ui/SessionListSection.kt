package com.helix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.SessionRowUi
import com.helix.app.chat.SessionSearchHitUi
import com.helix.app.chat.SessionSearchUiState

// The Compose DSL keeps each section in one composable; detekt's LongMethod and
// CyclomaticComplexMethod do not model UI composition well (each item/branch is
// declarative layout, not control flow), so they are suppressed per composable
// (same convention as the app shell).
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
internal fun SessionListSection(
    sessions: List<SessionRowUi>,
    search: SessionSearchUiState,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onTasks: () -> Unit,
    onRename: (String) -> Unit,
    onNavigation: () -> Unit,
    onProviders: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    needsProvider: Boolean = false,
) {
    var archivedOnly by rememberSaveable { mutableStateOf(false) }
    val visibleSessions = sessions.filter { it.isArchived == archivedOnly }
    BackHandler(archivedOnly) { archivedOnly = false }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("chat-session-list"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onNavigation, modifier = Modifier.testTag("open-navigation")) {
                    NavigationMenuIcon()
                }
                Text(
                    stringResource(if (archivedOnly) R.string.chat_archived_list else R.string.chat_session_header),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onTasks, modifier = Modifier.testTag("background-tasks-open")) {
                    Text(stringResource(R.string.background_tasks))
                }
                OutlinedButton(
                    onClick = onNew,
                    modifier = Modifier.testTag("chat-new-session"),
                ) {
                    Text(stringResource(R.string.chat_new_session))
                }
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = search.query,
                onValueChange = onSearch,
                placeholder = { Text(stringResource(R.string.chat_session_search_hint)) },
                trailingIcon = {
                    if (search.isSearching) {
                        TextButton(
                            onClick = { onSearch("") },
                            modifier = Modifier.testTag("chat-session-search-clear"),
                        ) {
                            Text(stringResource(R.string.chat_session_search_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("chat-session-search-field"),
                singleLine = true,
            )
        }
        if (search.isSearching) {
            item(key = "search-scope") {
                Text(
                    buildString {
                        append(stringResource(R.string.chat_session_search_scoped, search.scannedMessages))
                        if (search.truncated) {
                            append(" ")
                            append(stringResource(R.string.chat_session_search_truncated))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("chat-session-search-scope"),
                )
            }
            if (search.hits.isEmpty()) {
                item(key = "search-empty") {
                    Text(
                        stringResource(R.string.chat_session_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("chat-session-search-empty"),
                    )
                }
            } else {
                val activeHits = search.hits.filter { !it.isArchived }
                val archivedHits = search.hits.filter { it.isArchived }
                if (activeHits.isNotEmpty()) {
                    item(key = "search-group-active") {
                        Text(
                            stringResource(R.string.chat_session_search_group_active),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.testTag("chat-session-search-group-active"),
                        )
                    }
                    items(activeHits, key = { it.sessionId }) { hit -> SessionSearchHitRow(hit, onOpen) }
                }
                if (archivedHits.isNotEmpty()) {
                    item(key = "search-group-archived") {
                        Text(
                            stringResource(R.string.chat_session_search_group_archived),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.testTag("chat-session-search-group_archived"),
                        )
                    }
                    items(archivedHits, key = { it.sessionId }) { hit -> SessionSearchHitRow(hit, onOpen) }
                }
            }
        } else {
            if (needsProvider && !archivedOnly) {
                item(key = "provider-setup") {
                    OutlinedButton(onProviders, Modifier.testTag("chat-setup-provider")) {
                        Text(stringResource(R.string.chat_setup_provider))
                    }
                }
            }
            item(key = "archive-navigation") {
                TextButton({ archivedOnly = !archivedOnly }, Modifier.testTag("chat-archive-list-toggle")) {
                    Text(stringResource(if (archivedOnly) R.string.chat_active_list else R.string.chat_archived_list))
                }
            }
            if (visibleSessions.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(
                            if (archivedOnly) R.string.chat_empty_archive else R.string.chat_empty_sessions_hint,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleSessions, key = { it.id }) { session ->
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(
                        modifier =
                            Modifier
                                .testTag("chat-session-${session.id}")
                                .clickable { onOpen(session.id) }
                                .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(session.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                UiLabels.formatTime(session.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val noProvider = stringResource(R.string.chat_no_provider)
                        val archivedSuffix = stringResource(R.string.chat_archived_suffix)
                        Text(
                            buildString {
                                append(session.providerName ?: noProvider)
                                if (session.model != null) append(" · ${session.model}")
                                if (session.isArchived) append(archivedSuffix)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        session.directoryRef?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        Row {
                            TextButton({ onRename(session.id) }) { Text(stringResource(R.string.chat_rename)) }
                            TextButton(
                                onClick = { if (session.isArchived) onRestore(session.id) else onArchive(session.id) },
                                modifier = Modifier.testTag(if (session.isArchived) "chat-restore" else "chat-archive"),
                            ) {
                                Text(
                                    stringResource(
                                        if (session.isArchived) R.string.chat_restore else R.string.chat_archive,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One bounded search hit (HXA-191 slice): title + archive suffix, and the snippet when a body matched. */
@Composable
@Suppress("FunctionName")
private fun SessionSearchHitRow(
    hit: SessionSearchHitUi,
    onOpen: (String) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium) {
        Column(
            modifier =
                Modifier
                    .testTag("chat-session-hit-${hit.sessionId}")
                    .clickable { onOpen(hit.sessionId) }
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                buildString {
                    append(hit.title)
                    if (hit.isArchived) append(stringResource(R.string.chat_archived_suffix))
                },
                style = MaterialTheme.typography.titleSmall,
            )
            hit.messageSnippet?.let { snippet ->
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("chat-session-hit-snippet-${hit.sessionId}"),
                )
            }
        }
    }
}
