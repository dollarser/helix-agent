package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.storage.repository.SessionPermissionDraft

/**
 * The HXA-209 CUSTOM rule editor (ADR-PERMISSIONS-001 section 4). A CUSTOM snapshot is a FIXED
 * copy of a preset, then edited per operation effect — no live inheritance from later preset
 * changes. This composable is "dumb": it renders the copied-from preset (or a copy-from-a-preset
 * prompt when the session has no draft yet) and the per-effect ALLOW/ASK/DENY rules, and reports
 * the user's choice up via callbacks. The state and the Room writes (via
 * [SessionPermissionEditService]) live in the section's controller, so the UI never touches a DAO.
 *
 * It deliberately shows NO per-tool ASK or risk-level toggle (the two-state tool model has no ASK
 * to restore) and NO uniform network-egress DENY (out of scope by owner decision). Only the seven
 * CLOSED operation-effect keys exist; an effect absent from a draft resolves to ASK, never ALLOW.
 */
@Composable
@Suppress("FunctionName")
internal fun PermissionCustomEditor(
    draft: SessionPermissionDraft?,
    onCopyPreset: (SessionPermissionMode) -> Unit,
    onSetRule: (OperationEffect, OperationRule) -> Unit,
) {
    val sourcePreset = draft?.sourcePreset
    val rules = draft?.rules ?: emptyMap()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_perm_custom_rules_label))
        Text(
            stringResource(R.string.settings_perm_custom_merge_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sourcePreset == null) {
            Text(
                stringResource(R.string.settings_perm_custom_copy_prompt),
                style = MaterialTheme.typography.bodySmall,
            )
            PRESETS.forEach { preset ->
                OutlinedButton(
                    onClick = { onCopyPreset(preset) },
                    modifier = Modifier.testTag("settings-perm-custom-copy-${preset.name}"),
                ) {
                    Text(stringResource(preset.labelRes()))
                }
            }
        } else {
            Text(
                stringResource(R.string.settings_perm_custom_based_on, stringResource(sourcePreset.labelRes())),
                style = MaterialTheme.typography.bodySmall,
            )
            OperationEffect.values().forEach { effect ->
                RuleRow(
                    effect = effect,
                    current = rules[effect] ?: OperationRule.ASK,
                    onSet = { rule -> onSetRule(effect, rule) },
                )
            }
        }
    }
}

/**
 * One operation-effect row: its localized label and the three ALLOW/ASK/DENY options, the current
 * one rendered filled. An effect absent from the draft is shown as ASK (the fail-closed default a
 * missing CUSTOM category resolves to).
 */
@Composable
@Suppress("FunctionName")
private fun RuleRow(
    effect: OperationEffect,
    current: OperationRule,
    onSet: (OperationRule) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(effect.labelRes()))
        }
        RULES.forEach { rule ->
            val rowTestTag = "settings-perm-rule-${effect.name}-${rule.name}"
            if (rule == current) {
                Button(onClick = { onSet(rule) }, modifier = Modifier.testTag(rowTestTag)) {
                    Text(stringResource(rule.labelRes()))
                }
            } else {
                OutlinedButton(onClick = { onSet(rule) }, modifier = Modifier.testTag(rowTestTag)) {
                    Text(stringResource(rule.labelRes()))
                }
            }
        }
    }
}

/** The localized label for a mode. Tool/schema/enum names are never translated. */
internal fun SessionPermissionMode.labelRes(): Int =
    when (this) {
        SessionPermissionMode.FULL_ACCESS -> R.string.settings_perm_mode_full_access
        SessionPermissionMode.WORKSPACE -> R.string.settings_perm_mode_workspace
        SessionPermissionMode.READ_ONLY -> R.string.settings_perm_mode_read_only
        SessionPermissionMode.CUSTOM -> R.string.settings_perm_mode_custom
    }

/** The localized label for an operation-effect key (a closed, versioned set). */
internal fun OperationEffect.labelRes(): Int =
    when (this) {
        OperationEffect.FILE_READ_WORKSPACE -> R.string.settings_perm_effect_file_read_workspace
        OperationEffect.FILE_READ_EXTERNAL -> R.string.settings_perm_effect_file_read_external
        OperationEffect.FILE_MUTATION_WORKSPACE -> R.string.settings_perm_effect_file_mutation_workspace
        OperationEffect.FILE_MUTATION_EXTERNAL -> R.string.settings_perm_effect_file_mutation_external
        OperationEffect.REMOTE_BUSINESS_MUTATION -> R.string.settings_perm_effect_remote_business_mutation
        OperationEffect.DEVICE_SYSTEM_MUTATION -> R.string.settings_perm_effect_device_system_mutation
        OperationEffect.COMMAND_EXECUTION -> R.string.settings_perm_effect_command_execution
    }

/** The localized label for an ALLOW/ASK/DENY rule value. */
internal fun OperationRule.labelRes(): Int =
    when (this) {
        OperationRule.ALLOW -> R.string.settings_perm_rule_allow
        OperationRule.ASK -> R.string.settings_perm_rule_ask
        OperationRule.DENY -> R.string.settings_perm_rule_deny
    }

/** The ALLOW / ASK / DENY options offered for every operation effect. */
private val RULES = listOf(OperationRule.ALLOW, OperationRule.ASK, OperationRule.DENY)
