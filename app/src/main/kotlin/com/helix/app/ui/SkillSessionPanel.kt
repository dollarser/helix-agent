package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session-scoped Skill enablement. Installation/update/delete remain owned by Extensions. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun SkillSessionPanel(
    repository: SkillRepository,
    sessionId: String,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var rows by remember(sessionId) { mutableStateOf<List<SkillListItem>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("session-skills-panel")) {
                Text(stringResource(R.string.session_skills_hint))
                if (rows.isEmpty()) Text(stringResource(R.string.session_skills_empty))
                rows.forEach { row ->
                    Row {
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
                        Column {
                            Text(row.key.name)
                            Text(row.description)
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
