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
import com.helix.tools.automation.AutomationSessionStartStatus
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
        images: com.helix.tools.framework.ToolImagePublication,
    ) {
        val mobileUse = runtime ?: MobileUsePlugin(context.applicationContext, images).also { runtime = it }
        if (plugins.find(MobileUsePlugin.PLUGIN_ID) == null) plugins.register(mobileUse)
        appContext = context.applicationContext
        center = mobileUse.permissionCenter
    }

    fun scopeFor(toolName: String?): UserScope? =
        if (toolName?.startsWith("ui.") == true) center?.activeSession()?.scope else null

    @Composable
    @Suppress(
        "FunctionName",
        "LongMethod",
        "ReturnCount",
        "TooGenericExceptionCaught",
        "SwallowedException",
        "CyclomaticComplexMethod",
    )
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
        var allApplications by remember { mutableStateOf(false) }
        var limitMinutes by remember { mutableStateOf("0") }
        var limitActions by remember { mutableStateOf("0") }
        var startNotice by remember { mutableStateOf<Int?>(null) }
        var remainingSeconds by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                serviceState = permissionCenter.serviceState()
                activeSession = permissionCenter.activeSession()
                remainingSeconds = activeSession?.let {
                    java.time.Duration
                        .between(java.time.Instant.now(), it.scope.expiresAt)
                        .seconds
                        .coerceAtLeast(0)
                } ?: 0L
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
            Row {
                Checkbox(
                    checked = activeSession?.scope?.allApplications ?: allApplications,
                    onCheckedChange = {
                        allApplications = it
                        startNotice = null
                    },
                    enabled = !sessionActive,
                    modifier = Modifier.testTag("automation-all-applications"),
                )
                Text(stringResource(R.string.automation_whole_phone_label), Modifier.weight(1f))
            }
            Text(stringResource(R.string.automation_grant_explanation), style = MaterialTheme.typography.bodySmall)
            Row {
                OutlinedTextField(
                    value = limitMinutes,
                    onValueChange = { limitMinutes = it },
                    label = { Text(stringResource(R.string.automation_budget_minutes)) },
                    enabled = !sessionActive,
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("automation-budget-minutes"),
                )
                OutlinedTextField(
                    value = limitActions,
                    onValueChange = { limitActions = it },
                    label = { Text(stringResource(R.string.automation_budget_actions)) },
                    enabled = !sessionActive,
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("automation-budget-actions"),
                )
            }
            OutlinedTextField(
                value = packages,
                onValueChange = {
                    packages = it
                    startNotice = null
                },
                label = { Text(stringResource(R.string.automation_packages)) },
                enabled = !sessionActive && !allApplications,
                modifier = Modifier.fillMaxWidth().testTag("automation-packages"),
            )
            val settingsAuthorizationLabel = stringResource(R.string.automation_system_settings)
            Row {
                Checkbox(
                    checked = activeSession?.allowSystemSettings ?: (allowSystemSettings || allApplications),
                    onCheckedChange = { allowSystemSettings = it },
                    enabled = !sessionActive && !allApplications,
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
            startNotice?.let { notice ->
                Text(
                    stringResource(notice),
                    Modifier.testTag("automation-start-notice"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            activeSession?.let { active ->
                Text(
                    stringResource(
                        R.string.automation_lease_summary,
                        if (active.scope.expiresAt == java.time.Instant.MAX) {
                            stringResource(R.string.automation_until_stopped)
                        } else {
                            "${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')}"
                        },
                        if (active.scope.maxActions == 0) {
                            stringResource(R.string.automation_actions_unlimited, active.attemptedActions)
                        } else {
                            "${active.attemptedActions}/${active.scope.maxActions}"
                        },
                    ),
                    Modifier.testTag("automation-remaining"),
                )
            }
            com.helix.app.ui.SettingsActions(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(permissionCenter.accessibilitySettingsIntent())
                        } catch (_: Exception) {
                            startNotice = R.string.automation_service_unavailable
                        }
                    },
                    modifier = Modifier.testTag("automation-open-settings"),
                ) { Text(stringResource(R.string.automation_open_settings)) }
                Button(
                    onClick = {
                        startNotice = null
                        val raw = packages + if (allowSystemSettings) "," + settingsPackages.joinToString(",") else ""
                        val selected =
                            try {
                                if (allApplications) {
                                    emptySet()
                                } else {
                                    com.helix.tools.automation.AutomationTargetInput
                                        .parse(
                                            raw,
                                        )
                                }
                            } catch (_: IllegalArgumentException) {
                                startNotice = R.string.automation_target_invalid
                                null
                            }
                        if (selected != null) {
                            try {
                                val options =
                                    com.helix.tools.automation.AutomationSessionOptions.parse(
                                        limitMinutes,
                                        limitActions,
                                    )
                                if (!allApplications) permissionCenter.replaceAllowlist(selected)
                                val result =
                                    permissionCenter.startSession(
                                        selected,
                                        ttl = options.ttl,
                                        maxActions = options.maxActions,
                                        allowSystemSettings = allowSystemSettings,
                                        allApplications = allApplications,
                                    )
                                startNotice =
                                    when (result.status) {
                                        AutomationSessionStartStatus.STARTED -> {
                                            null
                                        }

                                        AutomationSessionStartStatus.SERVICE_NOT_CONNECTED -> {
                                            R.string.automation_service_unavailable
                                        }

                                        AutomationSessionStartStatus.EMPTY_TARGETS,
                                        AutomationSessionStartStatus.INVALID_PACKAGE,
                                        -> {
                                            R.string.automation_target_invalid
                                        }

                                        else -> {
                                            R.string.automation_start_failed
                                        }
                                    }
                                if (result.session != null) allowSystemSettings = false
                            } catch (_: IllegalArgumentException) {
                                startNotice = R.string.automation_budget_invalid
                            } catch (_: Exception) {
                                startNotice = R.string.automation_start_failed
                            }
                        }
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
                        allApplications = false
                    },
                    enabled = sessionActive,
                    modifier = Modifier.testTag("automation-stop"),
                ) { Text(stringResource(R.string.automation_stop)) }
            }
        }
    }
}
