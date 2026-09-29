package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.approval.SessionPermissionEditService
import com.helix.app.automation.AutomationModule
import com.helix.app.egress.EgressRuleSection
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.proot.ProotToolModule
import com.helix.app.provider.ProviderService
import com.helix.app.root.RootModule
import com.helix.app.runcontrol.RunControlStore
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.SafetyProfile
import com.helix.core.storage.repository.HighSensitivityRuleRepository

/** HXA-226 settings authority landing: detailed controls live on focused secondary routes. */
@Composable
@Suppress("FunctionName")
fun SettingsScreen(
    onDefaults: () -> Unit,
    onPermissions: () -> Unit,
    onAudit: () -> Unit,
    storageUsage: com.helix.app.storage.StorageUsageService? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-settings"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        storageUsage?.let { StorageUsageSection(it::snapshot) }
        SettingsLandingEntry(
            R.string.settings_defaults_title,
            R.string.settings_landing_defaults_desc,
            "settings-open-defaults",
            onDefaults,
        )
        SettingsLandingEntry(
            R.string.settings_permissions_safety_title,
            R.string.settings_landing_permissions_desc,
            "settings-open-permissions",
            onPermissions,
        )
        SettingsLandingEntry(
            R.string.settings_diagnostics_title,
            R.string.settings_landing_diagnostics_desc,
            "settings-open-audit",
            onAudit,
        )
    }
}

/** Language and defaults that affect future Turns/Goals, never current durable execution ownership. */
@Composable
@Suppress("FunctionName")
internal fun AppAgentDefaultsScreen(
    runControlStore: RunControlStore,
    chatService: com.helix.app.chat.ChatService?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-settings-defaults"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup { LanguageSection() }
        HorizontalDivider()
        SettingsGroup { RunControlSettingsSection(runControlStore) }
        SettingsGroup { GoalSettingsSection(runControlStore, chatService) }
    }
}

/**
 * Single primary home for Helix safety/authorization. Android grants are a child page reached via
 * [onSystemPermissions]; capability/readiness pages only deep-link here instead of duplicating the
 * same management controls.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
internal fun PermissionsSafetyScreen(
    profileStore: SafetyProfileStore,
    egressRules: HighSensitivityRuleRepository,
    lanScopeStore: com.helix.app.network.LanScopeStore?,
    chatService: com.helix.app.chat.ChatService?,
    sessionPermissionEdit: SessionPermissionEditService?,
    toolPipeline: ToolPipeline?,
    onSystemPermissions: () -> Unit,
) {
    val profile by profileStore.flow.collectAsStateWithLifecycle()
    var riskDialogOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-settings-permissions"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SafetyProfileSection(profile, profileStore) { riskDialogOpen = true }
        OutlinedButton(
            onClick = onSystemPermissions,
            modifier = Modifier.fillMaxWidth().testTag("settings-system-permissions"),
        ) {
            Text(stringResource(R.string.settings_system_permissions_title))
        }

        if (profile == SafetyProfile.ADVANCED) {
            SettingsGroup { RootModule.Section(profile) }
            SettingsGroup { AutomationModule.Section(profile) }
        }

        if (sessionPermissionEdit != null && toolPipeline != null) {
            SessionPermissionSection(sessionPermissionEdit, toolPipeline, chatService)
        }

        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE && profile == SafetyProfile.ADVANCED) {
            HorizontalDivider()
            lanScopeStore?.let { LanScopeSettingsSection(it) }
            EgressRuleSection(egressRules)
        }
    }

    AdvancedRiskDialog(
        open = riskDialogOpen,
        onConfirm = {
            profileStore.switchTo(SafetyProfile.ADVANCED)
            riskDialogOpen = false
        },
        onDismiss = { riskDialogOpen = false },
    )
}

/** Runtime installation/verification belongs to Setup, not the general Settings page. */
@Composable
@Suppress("FunctionName")
internal fun RuntimeSetupScreen(profileStore: SafetyProfileStore) {
    val profile by profileStore.flow.collectAsStateWithLifecycle()
    var runtimeRiskOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-setup-runtime"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup { QuickJsSettingsSection() }
        if (profile == SafetyProfile.ADVANCED && ProotToolModule.AVAILABLE) {
            SettingsGroup { ProotRuntimeSection() }
        } else {
            SettingsGroup {
                Text(stringResource(R.string.settings_proot_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
                            R.string.runtime_advanced_required
                        } else {
                            R.string.runtime_developer_required
                        },
                    ),
                    Modifier.testTag("runtime-availability-notice"),
                )
                if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
                    OutlinedButton({ runtimeRiskOpen = true }, Modifier.testTag("runtime-enable-advanced")) {
                        Text(stringResource(R.string.runtime_switch_advanced))
                    }
                }
            }
        }
    }
    AdvancedRiskDialog(
        open = runtimeRiskOpen,
        onConfirm = {
            profileStore.switchTo(SafetyProfile.ADVANCED)
            runtimeRiskOpen = false
        },
        onDismiss = { runtimeRiskOpen = false },
    )
}

/** Provider/model connection management is a first-class Configure destination. */
@Composable
@Suppress("FunctionName")
internal fun ModelsConnectionsScreen(
    providerService: ProviderService,
    chatService: com.helix.app.chat.ChatService,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-models"),
    ) {
        ProviderManager(providerService, chatService)
    }
}

/** Setup authority landing; readiness, detailed capabilities and Runtime are focused child routes. */
@Composable
@Suppress("FunctionName")
internal fun SetupScreen(
    onReadiness: () -> Unit,
    onCapabilities: () -> Unit,
    onRuntime: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-setup"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsLandingEntry(
            R.string.nav_readiness,
            R.string.setup_landing_readiness_desc,
            "setup-open-readiness",
            onReadiness,
        )
        SettingsLandingEntry(
            R.string.nav_capabilities,
            R.string.setup_landing_capabilities_desc,
            "setup-open-capabilities",
            onCapabilities,
        )
        SettingsLandingEntry(
            R.string.setup_runtime_title,
            R.string.setup_landing_runtime_desc,
            "setup-open-runtime",
            onRuntime,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun SettingsLandingEntry(
    titleRes: Int,
    descriptionRes: Int,
    tag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SafetyProfileSection(
    profile: SafetyProfile,
    profileStore: SafetyProfileStore,
    onEnterAdvanced: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_safety_section), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (profile == SafetyProfile.ADVANCED) {
                    stringResource(R.string.settings_current_advanced)
                } else {
                    stringResource(R.string.settings_current_standard)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("settings-profile-current"),
            )
        }
        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            if (profile == SafetyProfile.STANDARD) {
                OutlinedButton(onEnterAdvanced, Modifier.testTag("settings-advanced-switch")) {
                    Text(stringResource(R.string.settings_switch_to_advanced))
                }
            } else {
                OutlinedButton(
                    onClick = { profileStore.switchTo(SafetyProfile.STANDARD) },
                    modifier = Modifier.testTag("settings-advanced-exit"),
                ) {
                    Text(stringResource(R.string.settings_switch_back_standard))
                }
            }
            Text(
                stringResource(R.string.settings_advanced_m2_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings-advanced-note"),
            )
        } else {
            Text(
                stringResource(R.string.settings_consumer_standard_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings-advanced-absent"),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AdvancedRiskDialog(
    open: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (open) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.settings_advanced_confirm_title)) },
            text = { Text(stringResource(R.string.profile_advanced_risk_summary)) },
            confirmButton = {
                TextButton(onClick = onConfirm, modifier = Modifier.testTag("settings-risk-confirm")) {
                    Text(stringResource(R.string.settings_advanced_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-risk-cancel")) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag("settings-risk-dialog"),
        )
    }
}
