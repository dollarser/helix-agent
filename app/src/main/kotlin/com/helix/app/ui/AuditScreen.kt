package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.helix.core.model.RiskLevel

/**
 * The audit log page (roadmap HXA-036; doc 01 section 7): tool-dispatch audit records
 * filterable by 会话、工具、风险、日期, showing ONLY redacted records / bounded summaries.
 *
 * The page renders [com.helix.app.audit.AuditLogService] output — already parsed through
 * the payload allowlist into [com.helix.app.approval.DispatchAuditRecord] (no bodies
 * exist in the type to render). Filters narrow the newest bounded page in memory; the
 * service never loads the whole table (security doc section 10).
 */
@Composable
@Suppress("FunctionName")
fun AuditScreen(
    service: AuditLogService,
    sessions: List<SessionRowUi>,
) {
    var sessionId by remember { mutableStateOf<String?>(null) }
    var toolName by remember { mutableStateOf<String?>(null) }
    var risk by remember { mutableStateOf<RiskLevel?>(null) }
    var fromDay by remember { mutableStateOf("") }
    var toDay by remember { mutableStateOf("") }

    val filter =
        remember(sessionId, toolName, risk, fromDay, toDay) {
            AuditLogFilter.fromUi(sessionId, toolName, risk, fromDay, toDay)
        }
    // Pushes the filter to the service: it reloads the bounded page off the main thread
    // and republishes the StateFlows below (Room reads in composition crash).
    LaunchedEffect(filter) { service.setFilter(filter) }
    val records by service.records.collectAsStateWithLifecycle()
    val tools by service.toolNames.collectAsStateWithLifecycle()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AuditLogHeader()
        AuditSessionToolRiskFilters(
            sessions,
            tools,
            sessionId,
            onSession = { sessionId = it },
            toolName,
            onTool = { toolName = it },
            risk,
            onRisk = { risk = it },
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
                risk = null
                fromDay = ""
                toDay = ""
            },
        )

        // The filters apply to the loaded newest [AuditLogService.PAGE_LIMIT] rows only
        // (the documented bounded-page tradeoff; older records are never loaded here).
        Text(
            auditCountText(records.size),
            style = MaterialTheme.typography.bodyMedium,
        )

        Column(modifier = Modifier.testTag("audit-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            records.forEach { record ->
                AuditRow(record)
            }
        }
    }
}

/** The page count with the bounded-page caveat: "no records" must not read as "the whole
 * audit history is empty" — the filters act on the loaded newest page only. */
@Composable
private fun auditCountText(size: Int): String =
    if (size == 0) {
        stringResource(R.string.audit_count_empty, AuditLogService.PAGE_LIMIT)
    } else {
        stringResource(R.string.audit_count_total, size, AuditLogService.PAGE_LIMIT)
    }

/** The page title + the redaction note (what this page may and may not show). */
@Composable
@Suppress("FunctionName")
private fun AuditLogHeader() {
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
private fun AuditSessionToolRiskFilters(
    sessions: List<SessionRowUi>,
    tools: List<String>,
    sessionId: String?,
    onSession: (String?) -> Unit,
    toolName: String?,
    onTool: (String?) -> Unit,
    risk: RiskLevel?,
    onRisk: (RiskLevel?) -> Unit,
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
            tag = "audit-filter-risk",
            label = stringResource(R.string.approval_risk),
            placeholder = stringResource(R.string.audit_all_risks),
            options =
                RiskLevel.entries.map {
                    it.name to stringResource(ApprovalUiMapper.riskLabel(it))
                },
            selected = risk?.name,
            onSelect = { onRisk(it?.let { name -> RiskLevel.valueOf(name) }) },
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
    val risk =
        record.risk?.let { stringResource(ApprovalUiMapper.riskLabel(it)) }
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
                risk,
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
