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
import com.helix.app.proot.ProotToolModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Passive overview. Installation and maintenance stay in the private runtime process. */
@Composable
@Suppress("FunctionName")
internal fun ProotRuntimeSection() {
    var statusResource by remember { mutableStateOf<Int?>(null) }
    var entryError by remember { mutableStateOf<String?>(null) }
    var resumeRevision by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        entryError = null
        resumeRevision++
    }
    LaunchedEffect(resumeRevision) {
        statusResource = withContext(Dispatchers.IO) { ProotToolModule.installationStatusResource() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_proot_title), style = MaterialTheme.typography.titleMedium)
        Text(
            statusResource?.let { stringResource(it) } ?: "…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings-proot-status"),
        )
        Text(stringResource(R.string.settings_proot_description), style = MaterialTheme.typography.bodySmall)
        entryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(
            onClick = { entryError = ProotToolModule.openEnvironmentNote()?.text },
            modifier = Modifier.testTag("settings-proot-manage"),
        ) { Text(stringResource(R.string.settings_proot_manage)) }
    }
}
