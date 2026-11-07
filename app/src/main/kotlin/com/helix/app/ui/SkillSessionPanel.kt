package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.helix.app.R
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.UserSkillActivation
import com.helix.ui.shared.indicatedVerticalScroll
import com.helix.feature.conversation.R as ConversationR

@Composable
@Suppress("FunctionName")
internal fun SkillSessionPanel(
    repository: SkillRepository,
    sessionId: String,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onInvoke: (suspend (UserSkillActivation) -> Boolean)? = null,
    context: SkillUseContext = SkillUseContext(),
) {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(context)
    val state =
        remember(repository, sessionId) { SkillSessionState(repository, sessionId, scope, { latest.value.canApply() }) }
    var focus by rememberSaveable(sessionId, context.initial, stateSaver = skillKeySaver) {
        mutableStateOf(context.initial)
    }
    LaunchedEffect(state, state.revision) { state.reload() }
    val key = focus
    if (key != null) {
        SkillUseDialog(
            repository,
            sessionId,
            key,
            SkillUseActions(
                context,
                onInvoke,
                dismiss = {
                    if (context.initial !=
                        null
                    ) {
                        onDismiss()
                    } else {
                        focus = null
                        state.revision++
                    }
                },
                completed = onDismiss,
            ),
        )
    } else {
        if (state.loaded || state.failed) {
            SkillSessionListDialog(state, onInvoke != null, onManage, onDismiss) { focus = it }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SkillSessionList(
    state: SkillSessionState,
    invocable: Boolean,
    focus: (SkillKey) -> Unit,
) {
    Column(Modifier.indicatedVerticalScroll(rememberScrollState()).testTag("session-skills-panel")) {
        if (state.rows.isEmpty() && !state.failed) Text(stringResource(R.string.session_skills_empty))
        state.rows.forEach { row ->
            Row {
                if (!invocable) {
                    Checkbox(
                        row.enabled,
                        { state.enable(row.key, it) },
                        enabled = !state.busy,
                        modifier = Modifier.testTag("session-skill-${row.key.name}-${row.key.snapshotHash.take(8)}"),
                    )
                }
                Column(Modifier.weight(1f)) {
                    val (title, summary) = skillCatalogPresentation(row)
                    Text(title)
                    Text(summary)
                    if (invocable && row.invocation.userInvocable) {
                        TextButton({ focus(row.key) }, Modifier.testTag("skill-invoke-${row.key.name}")) {
                            Text(stringResource(R.string.extensions_use))
                        }
                    }
                }
            }
        }
        if (state.failed) {
            Text(stringResource(R.string.session_skills_failed))
            TextButton({ state.revision++ }) { Text(stringResource(R.string.skill_support_retry)) }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun SkillSessionListDialog(
    state: SkillSessionState,
    invocable: Boolean,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    focus: (SkillKey) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (!invocable) {
                        R.string.session_skills_title
                    } else {
                        ConversationR.string.skill_selection_title
                    },
                ),
            )
        },
        text = { SkillSessionList(state, invocable, focus) },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            TextButton({
                onDismiss()
                onManage()
            }, Modifier.testTag("session-skills-manage")) {
                Text(stringResource(R.string.session_skills_manage))
            }
        },
    )
}
