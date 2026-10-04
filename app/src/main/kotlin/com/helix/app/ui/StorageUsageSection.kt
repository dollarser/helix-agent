package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.storage.StorageUsageCategory
import com.helix.app.storage.StorageUsageEntry
import kotlinx.coroutines.CancellationException

/** Read-only usage opens directly; cleanup retains its separate explicit confirmation. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "SwallowedException")
internal fun StorageUsageScreen(
    cleanProviderEvidence: (suspend (String?) -> com.helix.app.privacy.ProviderEvidenceCleanup)? = null,
    load: suspend () -> List<StorageUsageEntry>,
) {
    var attempt by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<StorageUsageEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        entries = null
        failed = false
        try {
            entries = load()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-settings-storage"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.storage_usage_scope))
        entries?.forEach { entry ->
            val label =
                when (entry.category) {
                    StorageUsageCategory.RECORDS -> R.string.storage_usage_records
                    StorageUsageCategory.WORKSPACES -> R.string.storage_usage_workspaces
                    StorageUsageCategory.MEMORY -> R.string.storage_usage_memory
                }
            Text(
                stringResource(label) + ": " + formatBytes(entry.bytes) +
                    if (entry.complete) "" else " · " + stringResource(R.string.storage_usage_partial),
                Modifier.testTag("storage-usage-${entry.category.name.lowercase()}"),
            )
        }
        if (failed) Text(stringResource(R.string.storage_usage_failed), Modifier.testTag("storage-usage-error"))
        if (!failed && entries == null) Text(stringResource(R.string.storage_usage_loading))
        TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("storage-usage-refresh")) {
            Text(stringResource(R.string.storage_usage_refresh))
        }
        Text(stringResource(R.string.storage_usage_deletion))
        cleanProviderEvidence?.let { ProviderEvidenceCleanupSection(it) }
    }
}
