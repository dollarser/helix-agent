package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session-scoped Skill enablement. Installation/update/delete remain owned by Extensions. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod", "ThrowsCount")
// One bounded dialog; all three asynchronous UI operations propagate cancellation.
internal fun SkillSessionPanel(
    repository: SkillRepository,
    sessionId: String,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onInvoke: ((com.helix.extensions.skills.UserSkillActivation) -> Unit)? = null,
) {
    var rows by remember(sessionId) { mutableStateOf<List<SkillListItem>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var invoking by remember(sessionId) { mutableStateOf<SkillListItem?>(null) }
    var arguments by remember(sessionId) { mutableStateOf("") }

    invoking?.let { row ->
        AlertDialog(
            onDismissRequest = { invoking = null },
            title = { Text(row.key.name) },
            text = {
                OutlinedTextField(arguments, {
                    if (it.length <=
                        4096
                    ) {
                        arguments = it
                    }
                }, label = {
                    Text(stringResource(R.string.skill_invocation_arguments))
                }, modifier = Modifier.fillMaxWidth().testTag("skill-invocation-arguments"))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                val activation =
                                    withContext(
                                        Dispatchers.IO,
                                    ) { repository.prepareUserActivation(row.key, sessionId, arguments) }
                                onInvoke?.invoke(activation)
                                invoking = null
                                onDismiss()
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (_: Exception) {
                                failed = true
                                invoking =
                                    null
                            }
                        }
                    },
                    modifier =
                        Modifier.testTag(
                            "skill-invocation-confirm",
                        ),
                ) { Text(stringResource(R.string.skill_invocation_once)) }
            },
            dismissButton = { TextButton({ invoking = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    LaunchedEffect(sessionId, revision) {
        try {
            rows = withContext(Dispatchers.IO) { repository.list(sessionId) }
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_skills_title)) },
        text = {
            Column(Modifier.indicatedVerticalScroll(rememberScrollState()).testTag("session-skills-panel")) {
                Text(stringResource(if (onInvoke == null) R.string.session_skills_hint else R.string.skill_pick_hint))
                if (rows.isEmpty()) Text(stringResource(R.string.session_skills_empty))
                rows.forEach { row ->
                    Row {
                        if (onInvoke == null) {
                            Checkbox(
                                checked = row.enabled,
                                enabled = !busy,
                                onCheckedChange = { enabled ->
                                    busy = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                repository.setEnabled(
                                                    row.key,
                                                    enabled,
                                                    SkillEnablementScope.SESSION,
                                                    sessionId,
                                                )
                                            }
                                            failed = false
                                            revision += 1
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            failed = true
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                modifier =
                                    Modifier.testTag(
                                        "session-skill-${row.key.name}-${row.key.snapshotHash.take(8)}",
                                    ),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(row.key.name)
                            Text(row.description)
                            if (onInvoke != null && !row.enabled) Text(stringResource(R.string.connector_inactive))
                            if (onInvoke != null && row.invocation.userInvocable &&
                                row.invocation.unsupportedExecutionFields.isEmpty()
                            ) {
                                TextButton(
                                    onClick = {
                                        invoking = row
                                        arguments = ""
                                    },
                                    enabled = row.enabled && !busy,
                                    modifier = Modifier.testTag("skill-invoke-${row.key.name}"),
                                ) {
                                    Text(stringResource(R.string.skill_invocation_once))
                                }
                            }
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.session_skills_failed))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onManage()
                },
                modifier = Modifier.testTag("session-skills-manage"),
            ) {
                Text(stringResource(R.string.session_skills_manage))
            }
        },
    )
}
