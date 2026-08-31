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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderService
import com.helix.core.model.SafetyProfile

/**
 * The settings screen (HXA-028): the safety-profile section (ADR-0005/0006)
 * plus the provider-management section ([ProviderManager]).
 *
 * Profile rules:
 * - the CONSUMER build renders NO Advanced entry at all (ADR-0006: the
 *   consumer channel never offers a path from Standard into Advanced) and its
 *   store refuses any switch to ADVANCED (fail-closed);
 * - the DEVELOPER build offers the explicit switch, guarded by the in-app risk
 *   explanation (ADVANCED_RISK_SUMMARY) that states the ADR guarantees;
 * - the switch is a PURE state transition — M2 enables no capability from it
 *   (NFR-011: zero permission/Root/Runtime/network side effects), and the
 *   screen says so honestly instead of faking gated capabilities.
 */
@Composable
@Suppress("FunctionName", "LongMethod")
fun SettingsScreen(
    profileStore: SafetyProfileStore,
    providerService: ProviderService,
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
        Text("设置", style = MaterialTheme.typography.titleLarge)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("安全配置", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (profile == SafetyProfile.ADVANCED) {
                        "当前：Advanced"
                    } else {
                        "当前：Standard（默认）"
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
                        Text("切换到 Advanced")
                    }
                } else {
                    OutlinedButton(
                        onClick = { profileStore.switchTo(SafetyProfile.STANDARD) },
                        modifier = Modifier.testTag("settings-advanced-exit"),
                    ) {
                        Text("切换回 Standard")
                    }
                }
                Text(
                    ADVANCED_M2_NOTE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-advanced-note"),
                )
            } else {
                Text(
                    "本版本仅提供 Standard 配置，不提供 Advanced 入口（消费者渠道无 Standard → Advanced 路径，ADR-0005/0006）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-advanced-absent"),
                )
            }
        }

        HorizontalDivider()

        ProviderManager(providerService)
    }

    if (riskDialogOpen) {
        AlertDialog(
            onDismissRequest = { riskDialogOpen = false },
            title = { Text("切换到 Advanced — 风险说明") },
            text = {
                Text(AdvancedProfileAvailability.ADVANCED_RISK_SUMMARY)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profileStore.switchTo(SafetyProfile.ADVANCED)
                        riskDialogOpen = false
                    },
                    modifier = Modifier.testTag("settings-risk-confirm"),
                ) {
                    Text("我已了解，确认切换")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { riskDialogOpen = false },
                    modifier = Modifier.testTag("settings-risk-cancel"),
                ) {
                    Text("取消")
                }
            },
            modifier = Modifier.testTag("settings-risk-dialog"),
        )
    }
}

private const val ADVANCED_M2_NOTE =
    "M2 说明：切换 Advanced 不启用任何新能力——零系统权限申请、零 Runtime 安装、" +
        "零 Root 会话、零新网络端点。每项高级能力将由后续里程碑单独启用、限定 scope 且可立即撤销。"
