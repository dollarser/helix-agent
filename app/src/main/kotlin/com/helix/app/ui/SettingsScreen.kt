package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.approval.SessionPermissionEditService
import com.helix.app.egress.EgressRuleService
import com.helix.app.permissions.ui.EgressRuleSection
import com.helix.app.profile.ChannelCapabilities
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.proot.ProotToolModule
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ui.ProviderManager
import com.helix.app.runcontrol.RunControlStore
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.SafetyProfile
import com.helix.ui.shared.indicatedVerticalScroll

/** General application preferences; agent defaults and storage have their own drawer entries. */
@Composable
@Suppress("FunctionName")
fun SettingsScreen(
    accountEntry: @Composable () -> Unit,
    speechSettings: @Composable () -> Unit,
    memory: com.helix.app.memory.MemoryService? = null,
    contentBackup: com.helix.app.backup.AppBackupService? = null,
) {
    var memoryOpen by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(false) }
    if (memoryOpen && memory != null) MemoryDialog(memory, null) { memoryOpen = false }
    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup { accountEntry() }
        SettingsGroup { LanguageSection() }
        SettingsGroup {
            TextButton(
                enabled = memory != null,
                onClick = { memoryOpen = true },
                modifier = Modifier.testTag("settings-memory"),
            ) {
                Text(stringResource(R.string.memory_title))
            }
        }
        SettingsGroup {
            speechSettings()
            SystemVoiceSettingsEntry()
        }
        SettingsGroup {
            com.helix.app.network
                .NetworkSettingsEntry()
        }
        contentBackup?.let { backup ->
            val context = androidx.compose.ui.platform.LocalContext.current
            SettingsGroup {
                com.helix.app.backup
                    .AppBackupEntry(backup) {
                        var owner: android.content.Context = context
                        while (owner is android.content.ContextWrapper &&
                            owner !is android.app.Activity
                        ) {
                            owner = owner.baseContext
                        }
                        try {
                            (owner as? android.app.Activity)?.finishAndRemoveTask()
                        } finally {
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    }
            }
        }
        AboutHelixSection()
    }
}

/** Defaults for future Turns/Goals, never current durable execution ownership. */
@Composable
@Suppress("FunctionName")
internal fun AppAgentDefaultsScreen(
    runControlStore: RunControlStore,
    chatService: com.helix.feature.conversation.application.ChatService?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-settings-defaults"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
    egressRules: EgressRuleService,
    lanScopeStore: com.helix.app.network.LanScopeStore?,
    sessionPermissionEdit: SessionPermissionEditService?,
    toolPipeline: ToolPipeline?,
    onSystemPermissions: () -> Unit,
) {
    val profile by profileStore.flow.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-settings-permissions"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_permissions_intro), style = MaterialTheme.typography.bodyMedium)
        SettingsGroup {
            Text(
                stringResource(R.string.settings_system_permissions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.settings_system_permissions_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onSystemPermissions,
                modifier = Modifier.fillMaxWidth().testTag("settings-system-permissions"),
            ) {
                Text(stringResource(R.string.permissions_manage))
            }
        }

        if (sessionPermissionEdit != null && toolPipeline != null) {
            SessionPermissionSection(sessionPermissionEdit, toolPipeline)
        }

        if (ChannelCapabilities.ADVANCED_AVAILABLE && profile == SafetyProfile.ADVANCED) {
            lanScopeStore?.let {
                SettingsDisclosure(
                    stringResource(R.string.settings_lan_title),
                    "settings-lan-options",
                    stringResource(R.string.settings_lan_summary),
                ) { LanScopeSettingsSection(it) }
            }
            SettingsDisclosure(
                stringResource(R.string.egress_title),
                "settings-egress-options",
                stringResource(R.string.settings_egress_summary),
            ) { EgressRuleSection(egressRules) }
        }
    }
}

/** Execution environment status and maintenance have a dedicated Settings entry. */
@Composable
@Suppress("FunctionName")
internal fun RuntimeSetupScreen(profileStore: SafetyProfileStore) {
    val profile by profileStore.flow.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
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
                    stringResource(R.string.runtime_developer_required),
                    Modifier.testTag("runtime-availability-notice"),
                )
            }
        }
    }
}

/** Provider/model connection management is a top-level destination. */
@Composable
@Suppress("FunctionName")
internal fun ModelsConnectionsScreen(
    providerService: ProviderService,
    initialSource: ProviderProvisioningKind? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-models"),
    ) {
        ProviderManager(providerService, initialSource)
    }
}
