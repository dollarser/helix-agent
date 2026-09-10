package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.helix.app.automation.AutomationModule
import com.helix.app.egress.EgressRuleSection
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.proot.ProotToolModule
import com.helix.app.provider.ProviderService
import com.helix.app.root.RootModule
import com.helix.app.runcontrol.RunControlStore
import com.helix.core.model.SafetyProfile
import com.helix.core.storage.repository.HighSensitivityRuleRepository

/**
 * The settings screen (HXA-028): the safety-profile section (ADR-0005/0006)
 * plus the provider-management section ([ProviderManager]).
 *
 * Profile rules:
 * - the CONSUMER build renders NO Advanced entry at all (ADR-0006: the
 *   consumer channel never offers a path from Standard into Advanced) and its
 *   store refuses any switch to ADVANCED (fail-closed);
 * - the DEVELOPER build offers the explicit switch, guarded by the in-app risk
 *   explanation (the profile_advanced_risk_summary resource) that states the ADR guarantees;
 * - the switch is a PURE state transition — M2 enables no capability from it
 *   (NFR-011: zero permission/Root/Runtime/network side effects), and the
 *   screen says so honestly instead of faking gated capabilities.
 *
 * HXA-068 adds the ADVANCED bounded high-sensitivity egress-rule section
 * ([EgressRuleSection]), shown ONLY when the profile is ADVANCED (and the
 * developer build offers Advanced); consumer/Standard never render it.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList") // Optional feature facades preserve preview/test hosts.
fun SettingsScreen(
    profileStore: SafetyProfileStore,
    providerService: ProviderService,
    egressRules: HighSensitivityRuleRepository,
    runControlStore: RunControlStore,
    connectorService: com.helix.app.connector.ConnectorService? = null,
    lanScopeStore: com.helix.app.network.LanScopeStore? = null,
    skillAuthoringService: com.helix.app.skills.SkillAuthoringService? = null,
    skillInstallationService: com.helix.app.skills.SkillInstallationService? = null,
) {
    val profile by profileStore.flow.collectAsStateWithLifecycle()
    var riskDialogOpen by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("screen-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_safety_section), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                    OutlinedButton(
                        onClick = { riskDialogOpen = true },
                        modifier = Modifier.testTag("settings-advanced-switch"),
                    ) {
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

        if (profile == SafetyProfile.ADVANCED && ProotToolModule.AVAILABLE) {
            ProotRuntimeSection()
        }

        HorizontalDivider()

        RootModule.Section(profile)

        AutomationModule.Section(profile)

        com.helix.app.companions
            .BundledRuntimeSection()

        LanguageSection()

        ProviderManager(providerService)

        HorizontalDivider()

        RunControlSettingsSection(runControlStore)

        skillAuthoringService?.let {
            com.helix.app.skills
                .SkillAuthoringSection(it)
        }

        if (skillAuthoringService != null && skillInstallationService != null) {
            com.helix.app.skills
                .SkillInstallationSection(skillAuthoringService, skillInstallationService)
        }
        connectorService?.let {
            com.helix.app.connector
                .ConnectorSection(it)
        }

        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE && profile == SafetyProfile.ADVANCED) {
            HorizontalDivider()
            lanScopeStore?.let { LanScopeSettingsSection(it) }
            EgressRuleSection(egressRules)
        }
    }

    if (riskDialogOpen) {
        AlertDialog(
            onDismissRequest = { riskDialogOpen = false },
            title = { Text(stringResource(R.string.settings_advanced_confirm_title)) },
            text = {
                Text(stringResource(R.string.profile_advanced_risk_summary))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profileStore.switchTo(SafetyProfile.ADVANCED)
                        riskDialogOpen = false
                    },
                    modifier = Modifier.testTag("settings-risk-confirm"),
                ) {
                    Text(stringResource(R.string.settings_advanced_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { riskDialogOpen = false },
                    modifier = Modifier.testTag("settings-risk-cancel"),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag("settings-risk-dialog"),
        )
    }
}
