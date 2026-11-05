package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.helix.app.R
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillRepository

@Composable
@Suppress("FunctionName")
internal fun SkillUseDialog(
    repository: SkillRepository,
    session: String,
    key: SkillKey,
    actions: SkillUseActions,
) {
    androidx.compose.runtime.key(repository, session, key) {
        SkillUseIdentityDialog(repository, session, key, actions)
    }
}

@Composable
@Suppress("FunctionName")
private fun SkillUseIdentityDialog(
    repository: SkillRepository,
    session: String,
    key: SkillKey,
    actions: SkillUseActions,
) {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(actions)
    val state = remember(repository, session, key) { SkillUseState(repository, session, key, { latest.value }, scope) }
    var arguments by rememberSaveable(key, session) { mutableStateOf("") }
    LifecycleResumeEffect(state) {
        state.revision++
        onPauseOrDispose {}
    }
    LaunchedEffect(state, state.revision) { state.reload() }
    SkillUseReview(state, arguments) { arguments = it }
    actions.context.plugins?.let { plugins ->
        state.dependency?.let { id ->
            com.helix.app.connector.ConnectorSessionPanel(
                plugins,
                session,
                actions.context.configure,
                pluginId = id,
                canApply = actions.context.canApply,
            ) {
                state.dependency = null
                state.revision++
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SkillUseReview(
    state: SkillUseState,
    arguments: String,
    changed: (String) -> Unit,
) {
    if (state.row == null && !state.failed) return
    AlertDialog(
        onDismissRequest = { if (!state.busy) state.actions().dismiss() },
        title = { Text(state.row?.let { skillCatalogPresentation(it).first } ?: state.key.name) },
        text = { SkillUseContent(state, arguments, changed) },
        confirmButton = {
            if (state.actions().invoke != null && state.row?.invocation?.userInvocable == true) {
                TextButton(
                    { state.invoke(arguments) },
                    enabled =
                        !state.busy && state.row?.enabled == true && state.row?.invocation?.userInvocable == true &&
                            state.snapshot?.web?.provided != false &&
                            state.snapshot
                                ?.unsupported
                                .orEmpty()
                                .isEmpty(),
                    modifier = Modifier.testTag("skill-invocation-confirm"),
                ) { Text(stringResource(R.string.skill_invocation_once)) }
            }
        },
        dismissButton = {
            TextButton({ if (!state.busy) state.actions().dismiss() }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun SkillUseContent(
    state: SkillUseState,
    arguments: String,
    changed: (String) -> Unit,
) {
    Column(Modifier.indicatedVerticalScroll(rememberScrollState()).testTag("skill-use-review")) {
        state.snapshot?.let { SkillSupportFacts(it) }
        val web = state.snapshot?.web
        if (web != null && !web.provided && web.pluginId != null) {
            TextButton(
                { state.dependency = web.pluginId },
                enabled = !state.busy,
                modifier = Modifier.testTag("skill-configure-dependency"),
            ) {
                Text(stringResource(R.string.skill_support_configure))
            }
        }
        state.row?.let { SkillUseOptions(state, it, arguments, changed) }
        if (state.failed) {
            Text(
                stringResource(R.string.skill_support_apply_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("skill-use-failed"),
            )
        }
        if (state.failed || web?.provided == false) {
            TextButton(
                { state.revision++ },
                enabled = !state.busy,
                modifier = Modifier.testTag("skill-use-retry"),
            ) { Text(stringResource(R.string.skill_support_retry)) }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SkillUseOptions(
    state: SkillUseState,
    row: SkillListItem,
    arguments: String,
    changed: (String) -> Unit,
) {
    if (state.snapshot
            ?.unsupported
            .orEmpty()
            .isNotEmpty()
    ) {
        return
    }
    if (!row.invocation.userInvocable && !row.invocation.modelInvocable) {
        Text(stringResource(R.string.skill_support_preview_only), Modifier.testTag("skill-preview-only"))
        return
    }
    Row {
        Text(stringResource(R.string.skill_support_session_enable), Modifier.weight(1f))
        Switch(row.enabled, state::enable, enabled = !state.busy, modifier = Modifier.testTag("skill-session-enable"))
    }
    Text(
        stringResource(
            if (row.invocation.modelInvocable) R.string.skill_support_discovery else R.string.skill_support_manual_only,
        ),
    )
    if (!row.enabled) Text(stringResource(R.string.skill_support_disabled))
    if (!row.invocation.userInvocable) Text(stringResource(R.string.skill_support_no_user))
    if (state.actions().invoke != null && row.invocation.userInvocable) {
        OutlinedTextField(
            arguments,
            { if (it.length <= 4096) changed(it) },
            enabled = !state.busy,
            label = { Text(stringResource(R.string.skill_invocation_arguments)) },
            modifier = Modifier.fillMaxWidth().testTag("skill-invocation-arguments"),
        )
        Text(stringResource(R.string.skill_support_once_hint))
    }
}
