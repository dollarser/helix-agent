package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.files.TransferItemStatus
import com.helix.app.files.TransferResult

/** The transfer (导入/导出) final-result panel: per-item outcome + the temp-reclaim count. */
@Composable
@Suppress("FunctionName")
internal fun TransferResultPanel(
    result: TransferResult,
    tag: String,
    actions: FilesScreenActions,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            actions.str(
                R.string.files_transfer_summary,
                result.completed.size,
                result.problems.size,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (result.reclaimedTempFiles > 0) {
            Text(
                actions.str(R.string.files_temp_reclaimed, result.reclaimedTempFiles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        result.items.take(MAX_FAILURE_DETAIL_LINES).forEach { item ->
            val mark =
                when (item.status) {
                    TransferItemStatus.COMPLETED -> actions.str(R.string.files_transfer_item_completed)
                    TransferItemStatus.CONFLICT -> actions.str(R.string.files_transfer_item_conflict)
                    TransferItemStatus.SKIPPED -> actions.str(R.string.files_transfer_item_skipped)
                    TransferItemStatus.CANCELLED -> actions.str(R.string.files_transfer_item_cancelled)
                    TransferItemStatus.FAILED -> actions.str(R.string.files_transfer_item_failed)
                }
            val detail =
                item.detail?.let { actions.str(R.string.files_transfer_item_detail, it) } ?: ""
            val verified = if (item.verified) actions.str(R.string.files_transfer_item_verified) else ""
            Text(
                actions.str(
                    R.string.files_transfer_item_line,
                    mark,
                    item.sourceLabel,
                    item.targetLabel,
                    detail,
                    verified,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (result.items.size > MAX_FAILURE_DETAIL_LINES) {
            Text(
                actions.str(R.string.files_items_overflow, result.items.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
