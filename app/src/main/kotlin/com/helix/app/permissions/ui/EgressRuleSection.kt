package com.helix.app.permissions.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.egress.EgressRuleDraft
import com.helix.app.egress.EgressRuleRow
import com.helix.app.egress.EgressRuleService
import com.helix.core.policy.RuleDuration
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * HXA-068 (ADR-0005/0012): the ADVANCED bounded high-sensitivity egress-rule management UI.
 *
 * This is the ONLY place rules are created (developer/Advanced build, ADVANCED profile); the
 * section is gated by the caller and never renders in consumer/Standard. A rule is bound to an
 * EXACT provider/MCP id + canonical origin + the app's scope + a fixed 1h/24h/7d/30d TTL — no
 * wildcard, no sliding renewal, no "allow all". [EgressRuleService] owns validation and storage;
 * this section owns only the draft and display/action state.
 *
 * The application service runs storage on IO and propagates cancellation and fatal failures.
 * If the store cannot be read, the section shows the error and the per-call approval path is
 * unaffected — a missing/broken store can never silently widen what is auto-approved
 * (ADR-0012: 存储损坏 fail closed; [com.helix.core.policy.LiveEgressRules] is the engine-side gate).
 */
@Composable
@Suppress("FunctionName", "LongMethod") // composable (PascalCase per Compose) + one form/list body of UI layout
fun EgressRuleSection(rules: EgressRuleService) {
    val scope = rememberCoroutineScope()
    var rows by remember(rules) { mutableStateOf<List<EgressRuleRow>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    // create-form state
    var targetIsProvider by remember { mutableStateOf(true) }
    var targetId by remember { mutableStateOf("") }
    var originUrl by remember { mutableStateOf("") }
    var ttl by remember { mutableStateOf(RuleDuration.DEFAULT) }

    suspend fun load() {
        rules
            .load()
            .fold(
                onSuccess = {
                    rows = it
                    loadError = null
                },
                onFailure = {
                    rows = null
                    loadError = it.egressMessage()
                },
            )
    }

    fun create() {
        formError = null
        val draft = EgressRuleDraft(targetIsProvider, targetId, originUrl, ttl)
        scope.launch {
            rules.create(draft).fold(
                onSuccess = {
                    // Preserve edits made while IO was in flight.
                    if (targetId == draft.targetId && originUrl == draft.origin && targetIsProvider == draft.provider) {
                        targetId = ""
                        originUrl = ""
                    }
                    load()
                },
                onFailure = { formError = it.egressMessage() },
            )
        }
    }

    fun revoke(id: String) {
        scope.launch {
            rules
                .revoke(id)
                .fold(
                    onSuccess = { load() },
                    onFailure = { loadError = it.egressMessage() },
                )
        }
    }

    LaunchedEffect(rules) { load() }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("egress-section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.egress_rule_help),
            modifier = Modifier.testTag("egress-rule-help"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("egress-create-form"),
        ) {
            com.helix.app.ui.SettingsActions {
                ChoiceButton(targetIsProvider, "egress-target-provider", "Provider") { targetIsProvider = true }
                ChoiceButton(!targetIsProvider, "egress-target-mcp", "MCP") { targetIsProvider = false }
            }
            OutlinedTextField(
                value = targetId,
                onValueChange = { targetId = it },
                label = { Text(stringResource(R.string.egress_target_id_label)) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("egress-target-id"),
            )
            OutlinedTextField(
                value = originUrl,
                onValueChange = { originUrl = it },
                label = { Text(stringResource(R.string.egress_origin_label)) },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("egress-origin"),
            )
            Text(stringResource(R.string.egress_ttl_hint), style = MaterialTheme.typography.bodySmall)
            com.helix.app.ui.SettingsActions {
                for (duration in RuleDuration.entries) {
                    ChoiceButton(ttl == duration, "egress-ttl-${duration.name}", stringResource(ttlRes(duration))) {
                        ttl = duration
                    }
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
            ) { Text(stringResource(R.string.egress_create_rule)) }
        }

        HorizontalDivider()

        val current = rows
        val currentError = loadError
        when {
            currentError != null -> {
                Text(
                    stringResource(R.string.egress_load_failed, currentError),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("egress-load-error"),
                )
                TextButton({ scope.launch { load() } }, modifier = Modifier.testTag("egress-retry")) {
                    Text(stringResource(R.string.chat_retry))
                }
            }

            current == null -> {
                Text(
                    stringResource(R.string.egress_loading),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("egress-loading"),
                )
            }

            current.isEmpty() -> {
                Text(
                    stringResource(R.string.egress_empty),
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
    stored: EgressRuleRow,
    onRevoke: () -> Unit,
) {
    val windowEnd =
        stored.expiresAt
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val expired = !Instant.now().isBefore(stored.expiresAt)
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
                stored.target,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("egress-rule-target"),
            )
            Text(
                stringResource(
                    R.string.egress_rule_detail,
                    stored.origin,
                    stored.category,
                    stored.scope,
                    windowEnd,
                    if (expired) stringResource(R.string.egress_rule_expired) else "",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("egress-rule-detail"),
            )
        }
        TextButton(
            onClick = onRevoke,
            modifier = Modifier.testTag("egress-rule-revoke"),
        ) { Text(stringResource(R.string.egress_revoke)) }
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

/** The TTL options as STABLE string-resource ids (HXA-069: resolved by the caller's composable). */
private fun ttlRes(duration: RuleDuration): Int =
    when (duration) {
        RuleDuration.HOURS_1 -> R.string.egress_ttl_hours_1
        RuleDuration.HOURS_24 -> R.string.egress_ttl_hours_24
        RuleDuration.DAYS_7 -> R.string.egress_ttl_days_7
        RuleDuration.DAYS_30 -> R.string.egress_ttl_days_30
    }

private fun Throwable.egressMessage(): String = message ?: javaClass.simpleName
