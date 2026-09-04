package com.helix.app.automation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.helix.app.R
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.UserScope
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import com.helix.tools.automation.AutomationTools
import com.helix.tools.automation.PermissionCenterAutomationToolPort
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.delay

/** Developer-only permission center and user-controlled AutomationSession. */
internal object AutomationModule {
    private var appContext: Context? = null
    private var center: AutomationPermissionCenter? = null

    @Synchronized
    fun register(
        context: Context,
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
    ) {
        if (center != null) return
        val permissionCenter = AutomationPermissionCenter(context.applicationContext)
        AutomationTools(PermissionCenterAutomationToolPort(permissionCenter)).register(registry, implementations)
        appContext = context.applicationContext
        center = permissionCenter
    }

    fun scopeFor(toolName: String?): UserScope? =
        if (toolName?.startsWith("ui.") == true) center?.activeSession()?.scope else null

    @Composable
    @Suppress("FunctionName", "LongMethod", "ReturnCount")
    fun Section(profile: SafetyProfile) {
        if (profile != SafetyProfile.ADVANCED) return
        val permissionCenter = center ?: return
        val context = appContext ?: return
        var serviceState by remember { mutableStateOf(permissionCenter.serviceState()) }
        var packages by remember { mutableStateOf(permissionCenter.allowlistedPackages().sorted().joinToString(",")) }
        var sessionActive by remember { mutableStateOf(permissionCenter.activeSession() != null) }
        LaunchedEffect(Unit) {
            while (true) {
                serviceState = permissionCenter.serviceState()
                sessionActive = permissionCenter.activeSession() != null
                delay(250)
            }
        }

        HorizontalDivider()
        Column(modifier = Modifier.fillMaxWidth().testTag("settings-automation-section")) {
            Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.automation_warning), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    R.string.automation_state,
                    serviceState.name,
                    if (sessionActive) "ACTIVE" else "INACTIVE",
                ),
            )
            OutlinedTextField(
                value = packages,
                onValueChange = { packages = it },
                label = { Text(stringResource(R.string.automation_packages)) },
                enabled = !sessionActive,
                modifier = Modifier.fillMaxWidth().testTag("automation-packages"),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { context.startActivity(permissionCenter.accessibilitySettingsIntent()) },
                    modifier = Modifier.testTag("automation-open-settings"),
                ) { Text(stringResource(R.string.automation_open_settings)) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        val selected =
                            packages
                                .split(',')
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .toSet()
                        permissionCenter.replaceAllowlist(selected)
                        permissionCenter.startSession(selected)
                        sessionActive = permissionCenter.activeSession() != null
                    },
                    enabled = serviceState == AutomationServiceState.CONNECTED && !sessionActive,
                    modifier = Modifier.testTag("automation-start"),
                ) { Text(stringResource(R.string.automation_start)) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(
                    onClick = {
                        permissionCenter.stopSession()
                        sessionActive = false
                    },
                    enabled = sessionActive,
                    modifier = Modifier.testTag("automation-stop"),
                ) { Text(stringResource(R.string.automation_stop)) }
            }
        }
    }
}
