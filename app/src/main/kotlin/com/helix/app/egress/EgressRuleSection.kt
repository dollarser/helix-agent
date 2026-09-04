package com.helix.app.egress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.app.APP_SCOPE_ID
import com.helix.core.model.McpServerId
import com.helix.core.model.ProviderId
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.RuleDuration
import com.helix.core.policy.WorkspaceScope
import com.helix.core.storage.repository.HighSensitivityRuleRepository
import com.helix.core.storage.repository.StoredEgressRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * HXA-068 (ADR-0005/0012): the ADVANCED bounded high-sensitivity egress-rule management UI.
 *
 * This is the ONLY place rules are created (developer/Advanced build, ADVANCED profile); the
 * section is gated by the caller and never renders in consumer/Standard. A rule is bound to an
 * EXACT provider/MCP id + canonical origin + the app's scope + a fixed 1h/24h/7d/30d TTL — no
 * wildcard, no sliding renewal, no "allow all" (the value types reject wildcards at
 * construction and [HighSensitivityRule.withDuration] re-parses the origin fail-closed).
 *
 * Every Room call runs on [Dispatchers.IO] (HelixStorage does not allow main-thread queries).
 * If the store cannot be read, the section shows the error and the per-call approval path is
 * unaffected — a missing/broken store can never silently widen what is auto-approved
 * (ADR-0012: 存储损坏 fail closed; [com.helix.core.policy.LiveEgressRules] is the engine-side gate).
 */
@Composable
@Suppress("FunctionName", "LongMethod") // composable (PascalCase per Compose) + one form/list body of UI layout
fun EgressRuleSection(rules: HighSensitivityRuleRepository) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<StoredEgressRule>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    // create-form state
    var targetIsProvider by remember { mutableStateOf(true) }
    var targetId by remember { mutableStateOf("") }
    var originUrl by remember { mutableStateOf("") }
    var ttl by remember { mutableStateOf(RuleDuration.DEFAULT) }

    suspend fun load() {
        runCatching { withContext(Dispatchers.IO) { rules.all() } }
            .fold(
                onSuccess = {
                    rows = it
                    loadError = null
                },
                onFailure = {
                    rows = null
                    loadError = it.message
                },
            )
    }

    fun create() {
        formError = null
        // Fail-closed at the two seams the value types do not already cover: a blank/wildcard
        // id is rejected by ProviderId/McpServerId at construction, and a non-canonical origin
        // (non-http/https, userinfo, query, fragment) is rejected by NormalizedEndpoint.parse.
        runCatching {
            val target =
                if (targetIsProvider) {
                    EgressTarget.Provider(ProviderId(targetId))
                } else {
                    EgressTarget.Mcp(McpServerId(targetId))
                }
            HighSensitivityRule.withDuration(
                target,
                originUrl,
                WorkspaceScope(APP_SCOPE_ID),
                ttl,
                Instant.now(),
            )
        }.fold(
            onSuccess = { rule ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { rules.save(rule) } }
                        .fold(
                            onSuccess = {
                                targetId = ""
                                originUrl = ""
                                load()
                            },
                            onFailure = { formError = it.message },
                        )
                }
            },
            onFailure = { formError = it.message },
        )
    }

    fun revoke(id: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { rules.revoke(id) } }
                .fold(
                    onSuccess = { load() },
                    onFailure = { loadError = it.message },
                )
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("egress-section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "高敏出网规则",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("egress-section-title"),
        )
        Text(RULE_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("egress-create-form"),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton(targetIsProvider, "egress-target-provider", "Provider") { targetIsProvider = true }
                ChoiceButton(!targetIsProvider, "egress-target-mcp", "MCP") { targetIsProvider = false }
            }
            OutlinedTextField(
                value = targetId,
                onValueChange = { targetId = it },
                label = { Text("Provider / MCP ID（无通配符）") },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("egress-target-id"),
            )
            OutlinedTextField(
                value = originUrl,
                onValueChange = { originUrl = it },
                label = { Text("规范 origin（http/https，含 path）") },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("egress-origin"),
            )
            Text("有效期（一次授权，非滑动续期）", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (duration in RuleDuration.entries) {
                    ChoiceButton(ttl == duration, "egress-ttl-${duration.name}", ttlLabel(duration)) { ttl = duration }
                }
            }
            formError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("egress-form-error"),
                )
            }
            OutlinedButton(
                onClick = { create() },
                modifier = Modifier.testTag("egress-create-button"),
            ) { Text("创建规则") }
        }

        HorizontalDivider()

        val current = rows
        when {
            loadError != null -> {
                Text(
                    "规则加载失败：$loadError（逐次审批保持生效）",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("egress-load-error"),
                )
            }

            current == null -> {
                Text("加载中…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("egress-loading"))
            }

            current.isEmpty() -> {
                Text(
                    "暂无规则。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("egress-empty"),
                )
            }

            else -> {
                current.forEach { stored -> RuleRow(stored, onRevoke = { revoke(stored.id) }) }
            }
        }
    }
}

@Composable
@Suppress("FunctionName") // composable (PascalCase per Compose)
private fun RuleRow(
    stored: StoredEgressRule,
    onRevoke: () -> Unit,
) {
    val rule = stored.rule
    val target = rule.target
    val targetId =
        when (target) {
            is EgressTarget.Provider -> "provider:${target.id.value}"
            is EgressTarget.Mcp -> "mcp:${target.id.value}"
        }
    val windowEnd =
        rule.expiresAt
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val expired = !Instant.now().isBefore(rule.expiresAt)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("egress-rule-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                targetId,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("egress-rule-target"),
            )
            Text(
                "origin ${rule.origin.origin} · ${rule.dataCategory.name} · " +
                    "作用域 ${rule.scope.toScopeRef()} · 有效期至 $windowEnd${if (expired) "（已到期）" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("egress-rule-detail"),
            )
        }
        TextButton(
            onClick = onRevoke,
            modifier = Modifier.testTag("egress-rule-revoke"),
        ) { Text("撤销") }
    }
}

/**
 * A filled [Button] when [selected], otherwise an [OutlinedButton]; same [testTag] in either
 * state so the device test can find and tap the control regardless of which option is active.
 */
@Composable
@Suppress("FunctionName") // composable (PascalCase per Compose)
private fun ChoiceButton(
    selected: Boolean,
    testTag: String,
    label: String,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.testTag(testTag)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag(testTag)) { Text(label) }
    }
}

private fun ttlLabel(duration: RuleDuration): String =
    when (duration) {
        RuleDuration.HOURS_1 -> "1 小时"
        RuleDuration.HOURS_24 -> "24 小时"
        RuleDuration.DAYS_7 -> "7 天"
        RuleDuration.DAYS_30 -> "30 天"
    }

private const val RULE_HELP =
    "为高敏数据出网建立的有界规则：严格绑定具体 Provider/MCP、规范 origin 与 scope，仅在有效期内自动放行；" +
        "期满、撤销、切回 Standard 或任一绑定字段变化后立即失效，逐次审批保持不变。"
