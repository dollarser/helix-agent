package com.helix.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.helix.app.APP_SCOPE_ID
import com.helix.app.R
import com.helix.app.git.GitChange
import com.helix.app.git.GitChangeKind
import com.helix.app.git.GitStatus
import com.helix.app.git.GitWorkspaceReader
import com.helix.app.git.GitWorkspaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Git status / diff / changed-files surface (P0-B, research doc section 29): a first-class
 * page that reads the workspace repository's staged / unstaged / untracked changes on-device with
 * JGit, shows the branch and a per-file kind, and opens a unified diff for a file. Honest by
 * construction — a non-repo and a read error are distinct states, never a fabricated clean tree.
 *
 * State is read in a [LaunchedEffect] keyed on [revision] off the main thread, mirroring the
 * Capabilities / Artifacts refresh pattern, so the list recomputes only on entry and on an
 * explicit refresh.
 */
@Composable
@Suppress("FunctionName")
internal fun GitStatusScreenDestination() {
    val context = LocalContext.current
    val reader = remember { GitWorkspaceReader(workspaceRootOf(context)) }
    var revision by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<GitWorkspaceResult?>(null) }
    var selected by remember { mutableStateOf<GitChange?>(null) }

    LaunchedEffect(revision) {
        // JGit reads disk, so keep the read off the main thread (same as Capabilities/Artifacts).
        result = withContext(Dispatchers.IO) { reader.readStatus() }
    }

    Column(Modifier.fillMaxSize().testTag("screen-git")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("git-header"),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { revision += 1 }, modifier = Modifier.testTag("git-refresh")) {
                Text(stringResource(R.string.cap_refresh))
            }
        }
        when (val r = result) {
            null -> {
                Spacer(Modifier.fillMaxSize().testTag("git-loading"))
            }

            GitWorkspaceResult.NotARepository -> {
                stateText(
                    Modifier.testTag("git-not-repo"),
                    stringResource(R.string.git_not_a_repo),
                )
            }

            is GitWorkspaceResult.Error -> {
                stateText(Modifier.testTag("git-error"), stringResource(R.string.git_error_title) + "：" + r.message)
            }

            is GitWorkspaceResult.Ready -> {
                GitReadyContent(r.status, r.repoName) { selected = it }
            }
        }
    }

    selected?.let { s -> GitDiffDialog(reader, s) { selected = null } }
}

/** A centered, padded single-paragraph honest state (non-repo / error / clean). */
@Composable
@Suppress("FunctionName")
private fun stateText(
    modifier: Modifier,
    text: String,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(24.dp),
    )
}

/** A ready repository: its branch header, then either the clean state or the grouped change list. */
@Composable
@Suppress("FunctionName")
private fun GitReadyContent(
    status: GitStatus,
    repoName: String,
    onOpen: (GitChange) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.git_repo_label, repoName),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("git-repo"),
            )
            Text(
                stringResource(R.string.git_branch, status.branch),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("git-branch"),
            )
        }
        if (status.isClean) {
            stateText(Modifier.testTag("git-clean"), stringResource(R.string.git_clean))
        } else {
            GitChangeList(status, onOpen)
        }
    }
}

/** The changed files grouped into staged / unstaged / untracked sections. */
@Composable
@Suppress("FunctionName")
private fun GitChangeList(
    status: GitStatus,
    onOpen: (GitChange) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status.staged.isNotEmpty()) {
            item(key = "h-staged") { sectionLabel(stringResource(R.string.git_staged)) }
            items(status.staged, key = GitChange::id) { GitChangeRow(it, onOpen) }
        }
        if (status.unstaged.isNotEmpty()) {
            item(key = "h-unstaged") { sectionLabel(stringResource(R.string.git_unstaged)) }
            items(status.unstaged, key = GitChange::id) { GitChangeRow(it, onOpen) }
        }
        if (status.untracked.isNotEmpty()) {
            item(key = "h-untracked") { sectionLabel(stringResource(R.string.git_untracked)) }
            items(status.untracked, key = GitChange::id) { GitChangeRow(it, onOpen) }
        }
    }
}

/** A small overline labeling a change group. */
@Composable
@Suppress("FunctionName")
private fun sectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** One changed file: a tappable card with the path (and, for renames, its original path) and kind. */
@Composable
@Suppress("FunctionName")
private fun GitChangeRow(
    change: GitChange,
    onOpen: (GitChange) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = { onOpen(change) })
            .testTag("git-change-row-${change.path}"),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    change.path,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("git-change-path-${change.path}"),
                )
                change.originalPath?.let { original ->
                    Text(
                        original,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(kindResFor(change.kind)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("git-change-kind-${change.path}"),
            )
        }
    }
}

/**
 * The unified diff for one changed file, loaded off the main thread and shown in a monospace,
 * scrollable body; an honest note replaces an empty diff (e.g. an untracked / new file).
 */
@Composable
@Suppress("FunctionName")
private fun GitDiffDialog(
    reader: GitWorkspaceReader,
    change: GitChange,
    onDismiss: () -> Unit,
) {
    var diff by remember(change.id) { mutableStateOf("") }
    LaunchedEffect(change.id) {
        diff = withContext(Dispatchers.IO) { reader.diffFor(change.path) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_diff_title)) },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text(change.path, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    if (diff.isEmpty()) {
                        Text(stringResource(R.string.git_diff_empty))
                    } else {
                        Text(
                            diff,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("git-diff"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("git-diff-close")) {
                Text(stringResource(R.string.goal_close))
            }
        },
    )
}

/** A change kind's user-visible label (mapped at the UI boundary, HXA-069). */
private fun kindResFor(kind: GitChangeKind): Int =
    when (kind) {
        GitChangeKind.ADDED -> R.string.git_kind_added
        GitChangeKind.MODIFIED -> R.string.git_kind_modified
        GitChangeKind.DELETED -> R.string.git_kind_deleted
        GitChangeKind.RENAMED -> R.string.git_kind_renamed
        GitChangeKind.COPIED -> R.string.git_kind_copied
        GitChangeKind.TYPE_CHANGE -> R.string.git_kind_type_change
    }

/**
 * The workspace root the git reader inspects — the app's own scope directory (the same
 * `workspaces/<APP_SCOPE_ID>` root [com.helix.app.DefaultAppContainer] creates for the model's
 * file tools), so a repository the agent works on is the one the page reads.
 */
private fun workspaceRootOf(context: Context): File = File(context.filesDir, "workspaces/$APP_SCOPE_ID")
