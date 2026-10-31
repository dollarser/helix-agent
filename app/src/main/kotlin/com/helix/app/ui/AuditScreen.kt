package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.approval.AuditLogFilter
import com.helix.app.audit.AuditLogService
import com.helix.app.chat.SessionRowUi
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.model.ToolOperationClass

/**
 * The audit log page (roadmap HXA-036; doc 01 section 7): tool-dispatch audit records
 * filterable by 会话、工具、风险、日期, showing ONLY redacted records / bounded summaries.
 *
 * The page renders [com.helix.app.audit.AuditLogService] output — already parsed through
 * the payload allowlist into [com.helix.app.approval.DispatchAuditRecord] (no bodies
 * exist in the type to render). Bounded batches can continue through all history;
 * the service never loads the whole table at once.
 */
@Composable
@Suppress("FunctionName", "LongMethod") // One declarative filter form with a single scoped request.
fun AuditScreen(
    service: AuditLogService,
    sessions: List<SessionRowUi>,
    diagnostics: com.helix.app.diagnostics.DiagnosticReportService? = null,
    onReadiness: (() -> Unit)? = null,
    onCapabilities: (() -> Unit)? = null,
) {
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var toolName by rememberSaveable { mutableStateOf<String?>(null) }
    var operationClass by rememberSaveable { mutableStateOf<ToolOperationClass?>(null) }
    var fromDay by rememberSaveable { mutableStateOf("") }
    var toDay by rememberSaveable { mutableStateOf("") }
    var keyword by rememberSaveable { mutableStateOf("") }

    val filter =
        remember(sessionId, toolName, operationClass, fromDay, toDay, keyword) {
            AuditLogFilter.fromUi(sessionId, toolName, operationClass, fromDay, toDay, keyword)
        }
    // Pushes the filter to the service: it reloads the bounded page off the main thread
    // and republishes the StateFlows below (Room reads in composition crash).
    LaunchedEffect(filter) { service.setFilter(filter) }
    val records by service.records.collectAsStateWithLifecycle()
    val tools by service.toolNames.collectAsStateWithLifecycle()
    val history by service.history.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .indicatedVerticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("screen-audit"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TroubleshootingSection(diagnostics, onReadiness, onCapabilities)
        AuditLogHeader()
        OutlinedTextField(
            keyword,
            { keyword = it.take(256) },
            label = { Text(stringResource(R.string.audit_search_label)) },
            placeholder = { Text(stringResource(R.string.audit_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("audit-history-keyword"),
        )
        AuditSessionToolOperationFilters(
            sessions,
            tools,
            sessionId,
            onSession = { sessionId = it },
            toolName,
            onTool = { toolName = it },
            operationClass,
            onOperation = { operationClass = it },
        )
        AuditDateFilters(
            fromDay = fromDay,
            onFromDay = { fromDay = it },
            toDay = toDay,
            onToDay = { toDay = it },
        )
        AuditClearFiltersButton(
            onClear = {
                sessionId = null
                toolName = null
                operationClass = null
                fromDay = ""
                toDay = ""
                keyword = ""
            },
        )

        AuditHistoryControls(history, service)

        AuditRecords(records)
    }
}

@Composable
@Suppress("FunctionName")
private fun AuditRecords(records: List<com.helix.app.approval.DispatchAuditRecord>) {
    Column(modifier = Modifier.testTag("audit-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        records.forEach { record -> AuditRow(record) }
    }
}

@Composable
@Suppress("FunctionName")
private fun AuditHistoryControls(
    status: com.helix.app.audit.AuditHistoryStatus,
    service: AuditLogService,
) {
    when {
        status.loading -> {
            Text(stringResource(R.string.audit_search_loading))
        }

        status.failed -> {
            Text(stringResource(R.string.audit_search_failed))
        }

        else -> {
            Text(stringResource(R.string.audit_search_progress, status.page.records.size, status.page.scanned))
            Text(
                stringResource(
                    if (status.page.exhausted) R.string.audit_search_end else R.string.audit_search_remaining,
                ),
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            service::nextPage,
            enabled = !status.loading && !status.failed && status.page.next != null,
            modifier = Modifier.testTag("audit-history-next"),
        ) { Text(stringResource(R.string.audit_search_more)) }
        TextButton(
            service::retry,
            enabled = !status.loading,
            modifier = Modifier.testTag("audit-history-restart"),
        ) { Text(stringResource(R.string.audit_search_restart)) }
    }
}

/** The page title + the redaction note (what this page may and may not show). */
@Composable
@Suppress("FunctionName")
private fun AuditLogHeader() {
    Text(stringResource(R.string.settings_operation_records), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.audit_redaction_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Resets every filter at once. */
@Composable
@Suppress("FunctionName")
private fun AuditClearFiltersButton(onClear: () -> Unit) {
    OutlinedButton(onClick = onClear, modifier = Modifier.testTag("audit-clear-filters")) {
        Text(stringResource(R.string.audit_clear_filters))
    }
}

/** The 会话 / 工具 / 风险 pickers (roadmap HXA-036: the page filters by 会话、工具、风险、日期). */
@Composable
@Suppress("FunctionName", "LongParameterList") // one value+setter pair per pickable filter
private fun AuditSessionToolOperationFilters(
    sessions: List<SessionRowUi>,
    tools: List<String>,
    sessionId: String?,
    onSession: (String?) -> Unit,
    toolName: String?,
    onTool: (String?) -> Unit,
    operationClass: ToolOperationClass?,
    onOperation: (ToolOperationClass?) -> Unit,
) {
    SettingsActions {
        FilterSelect(
            tag = "audit-filter-session",
            label = stringResource(R.string.audit_filter_session),
            placeholder = stringResource(R.string.audit_all_sessions),
            options = sessions.map { it.id to it.title },
            selected = sessionId,
            onSelect = onSession,
        )
        FilterSelect(
            tag = "audit-filter-tool",
            label = stringResource(R.string.audit_filter_tool),
            placeholder = stringResource(R.string.audit_all_tools),
            options = tools.map { it to it },
            selected = toolName,
            onSelect = onTool,
        )
        FilterSelect(
            tag = "audit-filter-operationClass",
            label = stringResource(R.string.approval_operation),
            placeholder = stringResource(R.string.audit_all_operations),
            options =
                ToolOperationClass.entries.map {
                    it.name to stringResource(ApprovalUiMapper.operationLabel(it))
                },
            selected = operationClass?.name,
            onSelect = { onOperation(it?.let { name -> ToolOperationClass.valueOf(name) }) },
        )
    }
}

/** The 起始日期 / 结束日期 inputs (inclusive ISO days, system zone). */
@Composable
@Suppress("FunctionName")
private fun AuditDateFilters(
    fromDay: String,
    onFromDay: (String) -> Unit,
    toDay: String,
    onToDay: (String) -> Unit,
) {
    SettingsActions {
        OutlinedTextField(
            value = fromDay,
            onValueChange = onFromDay,
            modifier = Modifier.widthIn(min = 160.dp, max = 220.dp).testTag("audit-filter-from"),
            label = { Text(stringResource(R.string.audit_from_date)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = toDay,
            onValueChange = onToDay,
            modifier = Modifier.widthIn(min = 160.dp, max = 220.dp).testTag("audit-filter-to"),
            label = { Text(stringResource(R.string.audit_to_date)) },
            singleLine = true,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun FilterSelect(
    tag: String,
    label: String,
    placeholder: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.widthIn(min = 140.dp, max = 220.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.let { value -> options.firstOrNull { it.first == value }?.second }
                    ?: placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(placeholder) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AuditRow(record: com.helix.app.approval.DispatchAuditRecord) {
    val lines = auditLines(record)
    var details by remember(record.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("audit-row-${record.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(lines.first(), style = MaterialTheme.typography.titleSmall)
            Text(
                remember(record.startedAt) {
                    java.text.DateFormat
                        .getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
                        .format(java.util.Date(record.startedAt))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("audit-details-${record.id}")) {
                Text(stringResource(if (details) R.string.audit_details_hide else R.string.audit_details_show))
            }
            if (details) {
                lines.drop(1).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The localized audit-detail lines (safe label mapping; never raw exception text). */
@Composable
private fun auditLines(record: com.helix.app.approval.DispatchAuditRecord): List<String> {
    val code =
        record.code?.let { stringResource(ApprovalUiMapper.codeLabel(it)) }
            ?: stringResource(R.string.audit_unknown_code)
    val operationClass =
        record.operationClass?.let { stringResource(ApprovalUiMapper.operationLabel(it)) }
            ?: stringResource(R.string.audit_unknown)
    val source =
        record.decisionSource?.let { stringResource(ApprovalUiMapper.sourceLabel(it)) }
            ?: stringResource(R.string.audit_unknown)
    return buildList {
        add(
            stringResource(
                R.string.audit_row_summary,
                code,
                record.toolName ?: "?",
                record.toolVersion ?: "?",
                operationClass,
            ),
        )
        add(
            stringResource(
                R.string.audit_row_session_turn,
                record.sessionId ?: "?",
                record.turnId ?: "?",
            ),
        )
        add(
            stringResource(
                R.string.audit_row_decision_times,
                source,
                record.startedAt,
                record.finishedAt,
            ),
        )
        if (record.correlationId.isNotBlank()) {
            add(stringResource(R.string.audit_row_correlation, record.correlationId))
        }
    }
}
