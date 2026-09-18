package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.proot.CommandDetailState
import com.helix.app.proot.CommandLiveOutput
import com.helix.app.proot.CommandLogObserver
import com.helix.app.proot.CommandResultView
import kotlinx.coroutines.delay

/**
 * HXA-194: the command details page (独立详情页). It renders ONE command call's
 * [CommandResultView] — projected from persisted facts only (the turn, the tool call, its
 * settled result row, the prepared-job binding row and the locally persisted,
 * integrity-checked archive preview). Opening the page, re-opening it after rotation or
 * returning and re-entering reads the same facts and starts, replays, submits or
 * acknowledges NOTHING; the explicit reconciliation stays the existing session entry
 * (查看结果), which this page never duplicates.
 *
 * HXA-195 adds a bounded, read-only preview of an already running command. The
 * observer never starts the Runtime; the final archive remains authoritative.
 *
 * The route is its OWN NavHost entry (not one of the drawer's [com.helix.app.ShellDestination]
 * destinations): the system back returns to exactly the page the detail was opened from —
 * the task page or the chat tool row — and the explicit 返回会话 button opens the owning
 * session.
 */
internal const val COMMAND_DETAIL_ROUTE = "command-detail/{turnId}/{callId}"

internal fun commandDetailRoute(
    turnId: String,
    callId: String,
): String = "command-detail/$turnId/$callId"

@Composable
@Suppress("FunctionName")
internal fun CommandResultDetailScreen(
    service: ChatService,
    turnId: String,
    callId: String,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit = {},
) {
    var view by remember(turnId, callId) { mutableStateOf<CommandResultView?>(null) }
    var notFound by remember(turnId, callId) { mutableStateOf(false) }
    // A pure read on every composition of this (turnId, callId) pair — a rotation or
    // re-entry re-projects the SAME persisted facts; it cannot start anything.
    LaunchedEffect(turnId, callId) {
        do {
            view = service.commandResult(turnId, callId)
            notFound = view == null
            if (view?.state != CommandDetailState.RUNNING) break
            delay(500)
        } while (true)
    }
    val running = view?.state == CommandDetailState.RUNNING
    val live = rememberLiveOutput(view)
    Column(
        Modifier
            .fillMaxSize()
            .testTag("screen-command-detail"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("command-detail-back"),
            ) { Text(stringResource(R.string.command_detail_back)) }
            Text(
                stringResource(R.string.command_detail_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (notFound) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.command_detail_not_found),
                    modifier = Modifier.testTag("command-detail-missing"),
                )
            }
            return
        }
        val v = view ?: return
        Box(Modifier.weight(1f)) {
            SelectionContainer {
                val shown =
                    if (v.state == CommandDetailState.RUNNING && live != null) {
                        v.copy(stdout = live.stdout, stderr = live.stderr, truncated = live.truncated)
                    } else {
                        v
                    }
                CommandDetailBody(shown, live?.unavailable == true && running, onOpenSession)
            }
        }
    }
}

/**
 * The page's scrollable content: every section is a straight rendering of the projected
 * [CommandResultView] — nothing here writes state, so recomposition after rotation or
 * re-entry shows the same facts.
 */
@Composable
@Suppress("FunctionName")
private fun CommandDetailBody(
    v: CommandResultView,
    logUnavailable: Boolean,
    onOpenSession: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SummarySection(v)
        if (logUnavailable) {
            Text(stringResource(R.string.command_log_unavailable), Modifier.testTag("command-log-unavailable"))
        }
        OutcomeSection(v)
        OutputSection(v)
        BindingSection(v)
        Text(
            stringResource(R.string.command_detail_scope, v.scopeLabel),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            { onOpenSession(v.sessionId) },
            Modifier.testTag("command-detail-open-session"),
        ) { Text(stringResource(R.string.command_detail_open_session)) }
    }
}

/** The state headline, the running note, the command itself and its detail line. */
@Composable
@Suppress("FunctionName")
private fun SummarySection(v: CommandResultView) {
    Text(
        stringResource(stateTextRes(v.state)),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("command-detail-state-${v.state.name.lowercase()}"),
    )
    if (v.state == CommandDetailState.RUNNING) {
        Text(
            stringResource(R.string.command_detail_running_note),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (v.settlementPending) {
        Text(
            stringResource(R.string.command_detail_settlement_pending),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("command-detail-settlement-pending"),
        )
    }
    Text(
        stringResource(R.string.command_detail_command),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        v.commandText,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag("command-detail-command"),
    )
    v.detail?.let { detail ->
        Text(
            stringResource(R.string.command_detail_detail_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("command-detail-detail"),
        )
    }
}

/** The settled-outcome lines: exit code, the no-output notice and the truncation notice. */
@Composable
@Suppress("FunctionName")
private fun OutcomeSection(v: CommandResultView) {
    v.exitCode?.let { exitCode ->
        Text(
            stringResource(R.string.command_detail_exit_code, exitCode),
            modifier = Modifier.testTag("command-detail-exit"),
        )
    }
    if (v.noOutput) {
        Text(
            stringResource(R.string.command_detail_no_output),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("command-detail-no-output"),
        )
    }
    if (v.truncated) {
        Text(
            stringResource(R.string.command_detail_truncated),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The persisted streams (archive over content), their acknowledgement state and files. */
@Composable
@Suppress("FunctionName")
private fun OutputSection(v: CommandResultView) {
    if (v.stdout.isNotBlank()) {
        StreamSection(R.string.command_detail_stdout, v.stdout, "command-detail-stdout")
    }
    if (v.stderr.isNotBlank()) {
        StreamSection(R.string.command_detail_stderr, v.stderr, "command-detail-stderr")
    }
    v.acknowledged?.let { acknowledged ->
        Text(
            stringResource(
                if (acknowledged) {
                    R.string.command_detail_acknowledged
                } else {
                    R.string.command_detail_not_acknowledged
                },
            ),
        )
    }
    if (v.files.isNotEmpty()) {
        Text(
            stringResource(R.string.command_detail_files),
            style = MaterialTheme.typography.labelLarge,
        )
        v.files.forEach { file ->
            Text(
                "${file.path} · ${file.size} B · sha256 ${file.sha256.take(12)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("command-detail-file-${file.path}"),
            )
        }
    }
}

/** The prepared-job binding identity, present only when the audit trail proves it. */
@Composable
@Suppress("FunctionName")
private fun BindingSection(v: CommandResultView) {
    v.binding?.let { binding ->
        Text(
            stringResource(R.string.command_detail_binding),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(R.string.command_detail_job, binding.jobId),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            stringResource(R.string.command_detail_execution, binding.executionId),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            stringResource(R.string.command_detail_manifest, binding.inputManifestSha256),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** One output stream section: the section header + the bounded monospace content. */
@Composable
@Suppress("FunctionName")
private fun StreamSection(
    @androidx.annotation.StringRes headerRes: Int,
    content: String,
    contentTag: String,
) {
    Text(
        stringResource(headerRes),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        content,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth().testTag(contentTag),
    )
}

/** The distinct user-visible line per display state (HXA-194: every value its own text). */
private fun stateTextRes(state: CommandDetailState): Int =
    when (state) {
        CommandDetailState.RUNNING -> R.string.command_detail_state_running
        CommandDetailState.SUBMITTED -> R.string.command_detail_state_submitted
        CommandDetailState.SUCCEEDED -> R.string.command_detail_state_succeeded
        CommandDetailState.FAILED -> R.string.command_detail_state_failed
        CommandDetailState.CANCELLED -> R.string.command_detail_state_cancelled
        CommandDetailState.DENIED -> R.string.command_detail_state_denied
        CommandDetailState.UNKNOWN -> R.string.command_detail_state_unknown
        CommandDetailState.EVIDENCE_EXPIRED -> R.string.command_detail_state_evidence_expired
        CommandDetailState.READ_FAILED -> R.string.command_detail_state_read_failed
    }

@Composable
private fun rememberLiveOutput(view: CommandResultView?): CommandLiveOutput? {
    val binding = view?.binding
    val running = view?.state == CommandDetailState.RUNNING
    var live by remember(view?.callId) { mutableStateOf<CommandLiveOutput?>(null) }
    LaunchedEffect(binding, running) {
        if (binding != null && running) CommandLogObserver.observe(binding).collect { live = it }
    }
    return live
}
