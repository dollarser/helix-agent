package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.ConflictPolicy
import com.helix.app.files.FileManagerService.BatchItem
import com.helix.app.files.FileManagerService.FileEntry
import com.helix.app.files.FileManagerService.TrashEntryView
import com.helix.app.files.SortKey
import com.helix.app.files.TransferResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The destination + policy for a batch copy/move (a single file is a one-item batch). */
internal data class CopyMoveTarget(
    val move: Boolean,
    val sourceRels: List<String>,
)

/** The two file-list rendering modes (HXA-046 列表和网格视图). */
internal enum class ViewMode {
    LIST,
    GRID,
}

/** A breadcrumb segment: the root plus each path component (tappable to navigate up). */
@Composable
@Suppress("FunctionName")
internal fun BreadcrumbCrumb(
    label: String,
    isRoot: Boolean,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag(if (isRoot) "files-breadcrumb-root" else "files-breadcrumb-crumb-$label"),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (!isRoot) Text(">", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@Suppress("FunctionName")
internal fun SortButton(
    label: String,
    key: SortKey,
    current: SortKey,
    onPick: (SortKey) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (current == key) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        modifier =
            Modifier
                .testTag("files-sort-${key.name}")
                .clickable { onPick(key) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
@Suppress("FunctionName")
internal fun FileRow(
    entry: FileEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("files-entry-${entry.name}")
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("files-select-${entry.name}"),
        )
        Text(if (entry.isDirectory) "📁 " else "📄 ", style = MaterialTheme.typography.bodyLarge)
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sizeOrType =
                if (entry.isDirectory) {
                    stringResource(R.string.files_folder_type)
                } else {
                    formatSize(entry.sizeBytes)
                }
            val metaLabel = "$sizeOrType  ·  ${formatTime(entry.mtimeEpochMillis)}"
            Text(
                metaLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun GridFileItem(
    entry: FileEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("files-entry-${entry.name}")
                .clickable(onClick = onClick),
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("files-select-${entry.name}"),
        )
        Text(if (entry.isDirectory) "📁" else "📄", style = MaterialTheme.typography.headlineSmall)
        Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
@Suppress("FunctionName")
internal fun TrashRow(
    entry: TrashEntryView,
    canMutate: Boolean,
    onRestore: (TrashEntryView) -> Unit,
    onPurge: (TrashEntryView) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag("files-trash-entry-${entry.entryName}"),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.originalRelativePath,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canMutate) {
            TextButton(
                onClick = { onRestore(entry) },
                modifier = Modifier.testTag("files-trash-restore-${entry.entryName}"),
            ) {
                Text(stringResource(R.string.files_restore))
            }
            TextButton(
                onClick = { onPurge(entry) },
                modifier = Modifier.testTag("files-trash-purge-${entry.entryName}"),
            ) {
                Text(stringResource(R.string.files_purge_button))
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/** How many partial-failure lines the screen renders before collapsing to "… 等 N 项". */
internal const val MAX_FAILURE_DETAIL_LINES = 10

internal fun formatTime(millis: Long): String = if (millis <= 0L) "—" else timeFormat.format(Date(millis))

internal fun formatSize(bytes: Long): String =
    when {
        bytes < 0L -> "—"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

/** HXA-069: the conflict-policy option label as a STABLE string-resource id (resolved by the UI). */
internal fun policyLabel(policy: ConflictPolicy): Int =
    when (policy) {
        ConflictPolicy.ASK -> R.string.files_ask
        ConflictPolicy.SKIP -> R.string.files_skip
        ConflictPolicy.RENAME -> R.string.files_rename
        ConflictPolicy.OVERWRITE -> R.string.files_overwrite
    }

/** HXA-069: one batched item's outcome as a STABLE string-resource id (resolved by the UI). */
internal fun BatchItem.outcomeLabel(): Int =
    when (outcome) {
        BatchItem.Outcome.SUCCEEDED -> R.string.files_batch_succeeded
        BatchItem.Outcome.RENAMED -> R.string.files_batch_renamed
        BatchItem.Outcome.SKIPPED -> R.string.files_batch_skipped
        BatchItem.Outcome.FAILED -> R.string.files_batch_failed
    }

/** HXA-058: the import source shape (the two picker actions). */
internal enum class ImportMode {
    FILE,
    FOLDER,
}

/** HXA-058: the export destination shape (picker document vs authorized SAF tree). */
internal enum class ExportMode {
    NEW_DOC,
    TREE,
}

/**
 * HXA-058: the screen-level transfer status line, as a STABLE string-resource id + its positional
 * args (HXA-069: never locale text outside composables — the UI resolves it via stringResource).
 */
internal fun transferSummary(
    verb: String,
    result: TransferResult,
): Pair<Int, Array<Any>> =
    if (result.problems.isEmpty()) {
        R.string.files_transfer_complete_all to arrayOf(verb, result.completed.size)
    } else {
        R.string.files_transfer_complete_partial to arrayOf(verb, result.completed.size, result.problems.size)
    }
