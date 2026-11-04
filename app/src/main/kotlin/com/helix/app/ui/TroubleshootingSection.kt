package com.helix.app.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.diagnostics.DiagnosticReportService

@Composable
@Suppress("FunctionName")
internal fun TroubleshootingSection(
    diagnostics: DiagnosticReportService?,
    onReadiness: (() -> Unit)?,
    onCapabilities: (() -> Unit)?,
) {
    var appLogs by remember { mutableStateOf(false) }
    if (appLogs && diagnostics != null) AppLogSearchDialog(diagnostics) { appLogs = false }
    if (diagnostics == null && onReadiness == null && onCapabilities == null) return
    Text(stringResource(R.string.settings_troubleshooting), style = MaterialTheme.typography.titleMedium)
    SettingsActions {
        onReadiness?.let {
            OutlinedButton(it, Modifier.testTag("diagnostics-open-readiness")) {
                Text(stringResource(R.string.nav_readiness))
            }
        }
        onCapabilities?.let {
            OutlinedButton(it, Modifier.testTag("diagnostics-open-capabilities")) {
                Text(stringResource(R.string.nav_capabilities))
            }
        }
    }
    diagnostics?.let { DiagnosticReportSection(it::preview) }
    diagnostics?.let { DiagnosticStorageSection(it) }
    if (diagnostics != null) {
        OutlinedButton({ appLogs = true }, Modifier.testTag("app-log-search")) {
            Text(stringResource(R.string.app_log_search))
        }
    }
    HorizontalDivider()
}
