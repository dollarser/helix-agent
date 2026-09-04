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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.proot.ProotToolModule
import com.helix.app.provider.ProviderService
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        if (profile == SafetyProfile.ADVANCED && ProotToolModule.AVAILABLE) {
            ProotRuntimeSection()
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

/**
 * The PRoot Runtime section (HXA-085, developer + Advanced only): the stable
 * availability states the roadmap mandates (未安装 / 未验证 / 被禁用或强制停止 / 已验证)
 * + the two USER-CLICK actions — "验证 Runtime" (the only zero-Job bind; the process
 * is NOT a condition for tool availability) and "修复 Runtime" (the only repair-
 * activity path). No passive re-verification, no bind on entry.
 */
@Composable
@Suppress("FunctionName", "LongMethod")
private fun ProotRuntimeSection() {
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("…") }
    // The last user-click verification result (HXA-087 需更新 detection): the gate
    // label is bind-free and cannot see a moved lock; only the explicit
    // "验证 Runtime" click reveals a LOCK_MISMATCH and offers the re-baseline.
    var verifyNote by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    fun refresh() {
        statusText = ProotToolModule.verifyStatusLabel()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { refresh() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PRoot Runtime", style = MaterialTheme.typography.titleMedium)
        Text(
            "状态：$statusText（离线执行域，无 INTERNET；只随同签名 Runtime APK 更新，" +
                "无应用内自更新；Advanced/LAN scope 均不能为其联网）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings-proot-status"),
        )
        verifyNote?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("settings-proot-verify-note"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            verifyNote = ProotToolModule.verifyNowNote()
                            refresh()
                        }
                        busy = false
                    }
                },
                modifier = Modifier.testTag("settings-proot-verify"),
            ) {
                Text("验证 Runtime")
            }
            OutlinedButton(
                onClick = { ProotToolModule.openRepair() },
                modifier = Modifier.testTag("settings-proot-repair"),
            ) {
                Text("修复 Runtime")
            }
            OutlinedButton(
                onClick = { ProotToolModule.openLegalPage() },
                modifier = Modifier.testTag("settings-proot-legal"),
            ) {
                Text("许可证与来源")
            }
            OutlinedButton(
                onClick = { showRemoveConfirm = true },
                modifier = Modifier.testTag("settings-proot-remove"),
            ) {
                Text("删除 Runtime")
            }
        }
        if (verifyNote?.contains("需更新") == true) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val existed = ProotToolModule.rebaseline()
                            verifyNote =
                                if (existed) {
                                    "基线已重置（原锚点已删除）。请点「验证 Runtime」对新的内嵌基线重新验证。"
                                } else {
                                    "无已验证锚点可重置。"
                                }
                            refresh()
                        }
                    },
                    modifier = Modifier.testTag("settings-proot-rebaseline"),
                ) {
                    Text("重定基线（我已知晓基线变更）")
                }
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("删除 PRoot Runtime？") },
            text = {
                Text(
                    "将完整删除 Runtime 状态（RootFS、Job 记录、激活指针）。" +
                        "不会删除任何 Workspace 数据。删除后「bash」Tool 需重新安装/验证才可用。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirm = false
                        scope.launch(Dispatchers.IO) {
                            verifyNote = ProotToolModule.removeRuntimeNote()
                            refresh()
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("取消") }
            },
        )
    }
}

private const val ADVANCED_M2_NOTE =
    "M2 说明：切换 Advanced 不启用任何新能力——零系统权限申请、零 Runtime 安装、" +
        "零 Root 会话、零新网络端点。每项高级能力将由后续里程碑单独启用、限定 scope 且可立即撤销。"
