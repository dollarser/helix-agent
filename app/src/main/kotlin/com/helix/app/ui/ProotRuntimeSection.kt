package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.R
import com.helix.app.application.runtime.ProotInstallationStatus
import com.helix.app.proot.ProotToolModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Passive overview. Installation and maintenance stay in the private runtime process. */
@Composable
@Suppress("FunctionName")
internal fun ProotRuntimeSection(
    queryStatus: () -> ProotInstallationStatus = ProotToolModule::installationStatus,
    openManager: () -> Boolean = ProotToolModule::openEnvironment,
) {
    var status by remember { mutableStateOf<ProotInstallationStatus?>(null) }
    var entryFailed by remember { mutableStateOf(false) }
    var resumeRevision by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        entryFailed = false
        resumeRevision++
    }
    LaunchedEffect(resumeRevision) {
        status = withContext(Dispatchers.IO) { queryStatus() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_proot_title), style = MaterialTheme.typography.titleMedium)
        Text(
            status?.let { stringResource(it.labelRes()) } ?: "…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings-proot-status"),
        )
        Text(stringResource(R.string.settings_proot_description), style = MaterialTheme.typography.bodySmall)
        if (entryFailed) {
            Text(
                stringResource(R.string.runtime_entry_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings-proot-entry-error"),
            )
        }
        OutlinedButton(
            onClick = { entryFailed = !openManager() },
            modifier = Modifier.testTag("settings-proot-manage"),
        ) { Text(stringResource(R.string.settings_proot_manage)) }
    }
}

/** Shared UI projection for Settings and Capability Center; no adapter returns Android resources. */
internal fun ProotInstallationStatus.labelRes(): Int =
    when (this) {
        ProotInstallationStatus.NOT_AVAILABLE -> R.string.cap_status_unavailable
        ProotInstallationStatus.NOT_PREPARED -> R.string.settings_proot_not_installed
        ProotInstallationStatus.PREPARED -> R.string.settings_proot_verified
        ProotInstallationStatus.UPDATE_REQUIRED -> R.string.settings_proot_update_required
        ProotInstallationStatus.DAMAGED, ProotInstallationStatus.RESET_REQUIRED -> R.string.settings_proot_damaged
    }
