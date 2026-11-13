package com.helix.feature.conversation.presentation

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.feature.conversation.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The source is captured when opened; page navigation cannot retarget an in-flight grant. */
@Composable
// One source-bound permission editor; failures remain visible and never imply a successful save.
@Suppress(
    "FunctionName",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LongParameterList",
    "TooGenericExceptionCaught",
    "SwallowedException",
)
fun SessionAccessDialog(
    source: SessionRowUi,
    targets: List<SessionRowUi>,
    load: suspend () -> List<SessionAccessSelection>,
    save: suspend (SessionAccessSelection) -> Unit,
    onDismiss: () -> Unit,
    loadCreation: (suspend () -> Boolean)? = null,
    saveCreation: (suspend (Boolean) -> Unit)? = null,
) {
    var creationAllowed by remember(source.id) { mutableStateOf<Boolean?>(null) }
    var originalCreation by remember(source.id) { mutableStateOf<Boolean?>(null) }
    var grants by remember(source.id) { mutableStateOf<List<SessionAccessSelection>?>(null) }
    var selected by remember(source.id) { mutableStateOf<SessionAccessSelection?>(null) }
    var delegationDetails by remember(source.id) { mutableStateOf(false) }
    var failed by remember(source.id) { mutableStateOf(false) }
    var busy by remember(source.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val grantTargets = grants.orEmpty().map { it.targetSessionId }.toSet() - targets.map { it.id }.toSet()
    val selectableTargets = targets + grantTargets.map { SessionRowUi(it, it, 0, false, null, null) }
    LaunchedEffect(source.id) {
        try {
            grants = load()
            creationAllowed = loadCreation?.invoke()
            originalCreation = creationAllowed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.session_access_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.session_access_source, source.title, source.id))
                if (loadCreation != null) {
                    Row {
                        Checkbox(
                            checked = creationAllowed == true,
                            onCheckedChange = { creationAllowed = it },
                            enabled = creationAllowed != null && !busy,
                            modifier = Modifier.testTag("session-access-create"),
                        )
                        Text(stringResource(R.string.session_access_create))
                    }
                }
                if (failed) Text(stringResource(R.string.session_access_failed))
                if (selected == null) {
                    selectableTargets.filter { it.id != source.id }.forEach { target ->
                        TextButton(
                            enabled = grants != null && !busy,
                            onClick = {
                                selected = grants?.firstOrNull { it.targetSessionId == target.id }
                                    ?: SessionAccessSelection(target.id, false, false)
                            },
                            modifier = Modifier.testTag("session-access-target-${target.id}"),
                        ) {
                            Text("${target.title}\n${target.id}")
                        }
                    }
                } else {
                    val current = requireNotNull(selected)
                    val title = selectableTargets.firstOrNull { it.id == current.targetSessionId }?.title.orEmpty()
                    Text(stringResource(R.string.session_access_target, title, current.targetSessionId))
                    Row {
                        Checkbox(current.readHistory, {
                            selected = current.copy(readHistory = it)
                        }, enabled = !busy, modifier = Modifier.testTag("session-access-read"))
                        Text(stringResource(R.string.session_access_read))
                    }
                    Row {
                        Checkbox(current.manageMetadata, {
                            selected = current.copy(manageMetadata = it)
                        }, enabled = !busy, modifier = Modifier.testTag("session-access-manage"))
                        Text(stringResource(R.string.session_access_manage))
                    }
                    Row {
                        Checkbox(current.delegateTasks, {
                            selected = current.copy(delegateTasks = it)
                        }, enabled = !busy, modifier = Modifier.testTag("session-access-delegate"))
                        Text(stringResource(R.string.session_access_delegate))
                    }
                    TextButton(onClick = { delegationDetails = !delegationDetails }) {
                        Text(stringResource(R.string.session_access_delegate_details))
                    }
                    if (delegationDetails) Text(stringResource(R.string.session_access_delegate_explanation))
                    TextButton(onClick = {
                        selected = current.copy(readHistory = false, manageMetadata = false, delegateTasks = false)
                    }, enabled = !busy, modifier = Modifier.testTag("session-access-revoke")) {
                        Text(stringResource(R.string.session_access_revoke))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (selected != null || creationAllowed != originalCreation),
                onClick = {
                    val selection = selected
                    val creation = creationAllowed
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            selection?.let { save(it) }
                            if (creation != null && creation != originalCreation) {
                                requireNotNull(saveCreation).invoke(creation)
                                originalCreation = creation
                            }
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
                modifier = Modifier.testTag("session-access-save"),
            ) { Text(stringResource(R.string.session_access_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.session_access_cancel)) }
        },
    )
}
