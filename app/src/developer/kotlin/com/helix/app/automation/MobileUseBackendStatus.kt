package com.helix.app.automation

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
import com.helix.app.R
import com.helix.extensions.mobileuse.automation.AutomationClickBackend
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun backendSummary(backend: AutomationClickBackend?): Int =
    when (backend) {
        AutomationClickBackend.ROOT -> R.string.mobile_backend_root
        AutomationClickBackend.SHIZUKU -> R.string.mobile_backend_shizuku
        AutomationClickBackend.ACCESSIBILITY -> R.string.mobile_backend_accessibility
        null -> R.string.mobile_backend_partial
    }

/** Passive eligibility summary, not an execution probe or a grant. */
@Composable
@Suppress("FunctionName")
internal fun MobileUseBackendStatus(center: AutomationPermissionCenter) {
    var label by remember(center) { mutableStateOf<Int?>(null) }
    var service by remember(center) { mutableStateOf(AutomationServiceState.DISABLED) }
    LaunchedEffect(center) {
        while (true) {
            label =
                withContext(
                    Dispatchers.IO,
                ) {
                    backendSummary(center.preferredBackend(AutomationDeviceOperation.SNAPSHOT))
                }
            service = withContext(Dispatchers.IO) { center.serviceState() }
            delay(750)
        }
    }
    label?.let { Text(stringResource(it), Modifier.testTag("mobile-use-backend-summary")) }
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
}
