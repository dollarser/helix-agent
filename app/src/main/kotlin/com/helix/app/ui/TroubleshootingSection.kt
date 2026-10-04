package com.helix.app.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    HorizontalDivider()
}
