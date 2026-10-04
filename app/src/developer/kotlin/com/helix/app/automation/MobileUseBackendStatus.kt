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
import com.helix.tools.automation.AutomationBackendState
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun backendSummary(
    root: AutomationBackendState,
    shizuku: AutomationBackendState,
    accessibility: AutomationServiceState,
): Int =
    when {
        root == AutomationBackendState.READY -> R.string.mobile_backend_root
        shizuku == AutomationBackendState.READY -> R.string.mobile_backend_shizuku
        accessibility == AutomationServiceState.CONNECTED -> R.string.mobile_backend_accessibility
        else -> R.string.mobile_backend_partial
    }

/** Passive eligibility summary, not an execution probe or a grant. */
@Composable
@Suppress("FunctionName")
internal fun MobileUseBackendStatus(center: AutomationPermissionCenter) {
    var label by remember(center) { mutableStateOf<Int?>(null) }
    LaunchedEffect(center) {
        while (true) {
            label =
                withContext(
                    Dispatchers.IO,
                ) { backendSummary(center.rootState(), center.shizukuState(), center.serviceState()) }
            delay(750)
        }
    }
    label?.let { Text(stringResource(it), Modifier.testTag("mobile-use-backend-summary")) }
}
