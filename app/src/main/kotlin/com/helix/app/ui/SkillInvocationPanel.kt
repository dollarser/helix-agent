package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.helix.app.R
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.SkillSource
import com.helix.extensions.skills.UserSkillActivation
import com.helix.ui.shared.indicatedVerticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.helix.feature.conversation.R as ConversationR

private val invocationSelectionSaver =
    listSaver<List<SkillKey>, String>(
        save = { keys -> keys.flatMap { listOf(it.source.name, it.name, it.snapshotHash, it.ownerId) } },
        restore = { values -> values.chunked(4).map { SkillKey(SkillSource.valueOf(it[0]), it[1], it[2], it[3]) } },
    )

/** The composer selection is staged locally; only confirmation changes the current message. */
@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod", "TooGenericExceptionCaught")
internal fun SkillInvocationPanel(
    repository: SkillRepository,
    session: String,
    initial: List<SkillKey>,
    context: SkillUseContext,
    onApply: (List<UserSkillActivation>) -> Boolean,
    onDismiss: () -> Unit,
) {
    androidx.compose.runtime.key(repository, session) {
        SkillInvocationIdentityPanel(repository, session, initial, context, onApply, onDismiss)
    }
}

@Composable
// UI branches keep selection, failure recovery and dependency setup in the same modal.
@Suppress("FunctionName", "LongParameterList", "LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
private fun SkillInvocationIdentityPanel(
    repository: SkillRepository,
    session: String,
    initial: List<SkillKey>,
    context: SkillUseContext,
    onApply: (List<UserSkillActivation>) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(repository, session) { SkillSessionState(repository, session, scope, context.canApply) }
    var selected by rememberSaveable(session, stateSaver = invocationSelectionSaver) { mutableStateOf(initial) }
    var busy by remember(session) { mutableStateOf(false) }
    var failed by remember(session) { mutableStateOf(false) }
    var dependency by remember(session) { mutableStateOf<String?>(null) }
    var configuring by remember(session) { mutableStateOf(false) }
    LaunchedEffect(state, state.revision) { state.reload() }
    if (!state.loaded && !state.failed) return
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(ConversationR.string.skill_selection_title)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState()).testTag("skill-invocation-picker")) {
                Text(stringResource(R.string.skill_support_once_hint))
                if (state.rows.isEmpty() && !state.failed) Text(stringResource(R.string.session_skills_empty))
                state.rows.forEach { row ->
                    val selectable =
                        row.enabled && row.invocation.userInvocable &&
                            row.invocation.unsupportedExecutionFields.isEmpty()
                    val (title, description) = skillCatalogPresentation(row)
                    Row {
                        Checkbox(
                            checked = row.key in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + row.key else selected - row.key
                            },
                            enabled = !busy && (row.key in selected || (selectable && selected.size < 4)),
                            modifier =
                                Modifier.testTag("skill-select-${row.key.name}").semantics {
                                    contentDescription =
                                        title
                                },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(title)
                            Text(description)
                            if (!row.enabled) Text(stringResource(R.string.skill_invocation_enable_in_settings))
                            if (!row.invocation.userInvocable) Text(stringResource(R.string.skill_support_no_user))
                            if (row.invocation.unsupportedExecutionFields.isNotEmpty()) {
                                Text(stringResource(R.string.skill_support_preview_only))
                            }
                        }
                    }
                }
                if (selected.size >= 4) Text(stringResource(R.string.skill_invocation_limit))
                if (failed || state.failed) {
                    Text(
                        stringResource(R.string.skill_support_apply_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (dependency != null) {
                        TextButton({ configuring = true }, enabled = !busy) {
                            Text(stringResource(R.string.skill_support_configure))
                        }
                    }
                    TextButton({ state.revision++ }, enabled = !busy) {
                        Text(stringResource(R.string.skill_support_retry))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    busy = true
                    dependency = null
                    scope.launch {
                        try {
                            val activations =
                                withContext(Dispatchers.IO) {
                                    val current = repository.listSelectable(session).associateBy { it.key }
                                    selected.map { key ->
                                        check(current[key]?.enabled == true)
                                        val support = context.plugins?.skillSupport?.inspect(key, session)
                                        if (support?.web?.provided == false) dependency = support.web.pluginId
                                        check(support?.web?.provided != false)
                                        check(support?.unsupported.orEmpty().isEmpty())
                                        repository.prepareUserActivation(key, session, "")
                                    }
                                }
                            check(context.canApply())
                            check(onApply(activations))
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && !state.failed,
                modifier = Modifier.testTag("skill-selection-confirm"),
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
        },
    )
    if (configuring) {
        context.plugins?.let { plugins ->
            dependency?.let { id ->
                com.helix.app.connector.ConnectorSessionPanel(
                    plugins,
                    session,
                    context.configure,
                    pluginId = id,
                    canApply = context.canApply,
                ) {
                    configuring = false
                    state.revision++
                }
            }
        }
    }
}
