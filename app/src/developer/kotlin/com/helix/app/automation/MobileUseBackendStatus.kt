package com.helix.app.automation

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.mobileuse.automation.AutomationClickBackend
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun backendSummary(backend: AutomationClickBackend?): Int =
    when (backend) {
        AutomationClickBackend.ROOT -> R.string.mobile_backend_root
        AutomationClickBackend.SHIZUKU -> R.string.mobile_backend_shizuku
        AutomationClickBackend.ACCESSIBILITY -> R.string.mobile_backend_accessibility
        null -> R.string.mobile_backend_partial
    }

private fun compactBackendSummary(label: Int): Int =
    when (label) {
        R.string.mobile_backend_root -> R.string.mobile_use_ready_root
        R.string.mobile_backend_shizuku -> R.string.mobile_use_ready_shizuku
        R.string.mobile_backend_accessibility -> R.string.mobile_use_ready_accessibility
        else -> R.string.mobile_use_needs_access
    }

/** Passive eligibility summary, not an execution probe or a grant. */
@Composable
@Suppress("FunctionName")
internal fun MobileUseBackendStatus(center: AutomationPermissionCenter) {
    var label by remember(center) { mutableStateOf<Int?>(null) }
    var service by remember(center) { mutableStateOf(AutomationServiceState.DISABLED) }
    var details by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    com.helix.app.ui.ForegroundRefresh(center) {
        try {
            label =
                withContext(
                    Dispatchers.IO,
                ) {
                    backendSummary(center.preferredBackend(AutomationDeviceOperation.SNAPSHOT))
                }
            service = withContext(Dispatchers.IO) { center.serviceState() }
            failed = false
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    if (failed) {
        Text(stringResource(R.string.permissions_settings_unavailable))
    } else {
        label?.let { Text(stringResource(compactBackendSummary(it)), Modifier.testTag("mobile-use-backend-summary")) }
    }
    TextButton({ details = !details }, Modifier.testTag("mobile-use-connection-details")) {
        Text(stringResource(if (details) R.string.tool_details_hide else R.string.tool_details_show))
    }
    if (details) MobileUseConnectionDetails(service)
}

@Composable
@Suppress("FunctionName")
private fun MobileUseConnectionDetails(service: AutomationServiceState) {
    Text(stringResource(R.string.automation_backend_priority))
    Text(
        stringResource(
            when (service) {
                AutomationServiceState.CONNECTED -> R.string.mobile_accessibility_connected
                AutomationServiceState.ENABLED_DISCONNECTED -> R.string.mobile_use_accessibility_disconnected
                AutomationServiceState.DISABLED -> R.string.mobile_accessibility_disabled
                AutomationServiceState.CHECK_FAILED -> R.string.permissions_settings_unavailable
            },
        ),
        Modifier.testTag("mobile-use-accessibility-runtime"),
    )
    MobileUseRootConnection()
}
