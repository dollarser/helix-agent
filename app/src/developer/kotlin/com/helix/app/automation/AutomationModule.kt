package com.helix.app.automation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.UserScope
import com.helix.extensions.mobileuse.MobileUsePlugin
import com.helix.extensions.plugin.PluginRegistry
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import kotlinx.coroutines.delay

/** Developer host surface for the bundled Mobile Use Plugin and its user-controlled session. */
internal object AutomationModule {
    private var appContext: Context? = null
    private var center: AutomationPermissionCenter? = null
    private var runtime: MobileUsePlugin? = null

    @Synchronized
    fun register(
        context: Context,
        plugins: PluginRegistry,
    ) {
        val mobileUse = runtime ?: MobileUsePlugin(context.applicationContext).also { runtime = it }
        if (plugins.find(MobileUsePlugin.PLUGIN_ID) == null) plugins.register(mobileUse)
        appContext = context.applicationContext
        center = mobileUse.permissionCenter
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
        var activeSession by remember { mutableStateOf(permissionCenter.activeSession()) }
        val sessionActive = activeSession != null
        val settingsPackages = remember { permissionCenter.systemSettingsPackages() }
        var pauseReason by remember { mutableStateOf(permissionCenter.pauseReason()) }
        var resumePackage by remember { mutableStateOf("") }
        var recoveryResult by remember { mutableStateOf<Boolean?>(null) }
        var allowSystemSettings by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                serviceState = permissionCenter.serviceState()
                activeSession = permissionCenter.activeSession()
                pauseReason = permissionCenter.pauseReason()
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
            val settingsAuthorizationLabel = stringResource(R.string.automation_system_settings)
            Row {
                Checkbox(
                    checked = activeSession?.allowSystemSettings ?: allowSystemSettings,
                    onCheckedChange = { allowSystemSettings = it },
                    enabled = !sessionActive,
                    modifier =
                        Modifier.testTag("automation-system-settings").semantics {
                            contentDescription = settingsAuthorizationLabel
                        },
                )
                Text(settingsAuthorizationLabel, modifier = Modifier.weight(1f))
            }
            Text(
                stringResource(
                    R.string.automation_system_settings_detail,
                    settingsPackages.sorted().joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (sessionActive && pauseReason != null) {
                Text(stringResource(R.string.automation_paused, pauseReason!!.name))
                OutlinedTextField(
                    value = resumePackage,
                    onValueChange = {
                        resumePackage = it
                        recoveryResult = null
                    },
                    label = { Text(stringResource(R.string.automation_resume_target)) },
                    supportingText = {
                        Text(
                            activeSession!!
                                .scope.allowedPackages
                                .sorted()
                                .joinToString(", "),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("automation-resume-target"),
                )
                Button(
                    onClick = { recoveryResult = permissionCenter.requestResumeOnTarget(resumePackage.trim()) },
                    modifier = Modifier.testTag("automation-resume"),
                ) { Text(stringResource(R.string.automation_resume)) }
                recoveryResult?.let { accepted ->
                    Text(
                        stringResource(
                            if (accepted) R.string.automation_resume_pending else R.string.automation_resume_refused,
                        ),
                    )
                }
            }
            com.helix.app.ui.SettingsActions(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { context.startActivity(permissionCenter.accessibilitySettingsIntent()) },
                    modifier = Modifier.testTag("automation-open-settings"),
                ) { Text(stringResource(R.string.automation_open_settings)) }
                Button(
                    onClick = {
                        val selected =
                            packages
                                .split(',')
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .toSet() + if (allowSystemSettings) settingsPackages else emptySet()
                        permissionCenter.replaceAllowlist(selected)
                        permissionCenter.startSession(selected, allowSystemSettings = allowSystemSettings)
                        allowSystemSettings = false
                        activeSession = permissionCenter.activeSession()
                    },
                    enabled = serviceState == AutomationServiceState.CONNECTED && !sessionActive,
                    modifier = Modifier.testTag("automation-start"),
                ) { Text(stringResource(R.string.automation_start)) }
                OutlinedButton(
                    onClick = {
                        permissionCenter.stopSession()
                        activeSession = null
                        allowSystemSettings = false
                    },
                    enabled = sessionActive,
                    modifier = Modifier.testTag("automation-stop"),
                ) { Text(stringResource(R.string.automation_stop)) }
            }
        }
    }
}
