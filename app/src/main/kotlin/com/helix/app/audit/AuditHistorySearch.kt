package com.helix.app.audit

import com.helix.app.approval.AuditLogFilter
import com.helix.app.approval.DispatchAuditRecord
import com.helix.app.approval.StorageAuditSink
import com.helix.core.storage.repository.AuditEventCursor
import com.helix.core.storage.repository.AuditEventPage
import java.time.ZoneId

data class AuditHistoryPage(
    val records: List<DispatchAuditRecord> = emptyList(),
    val toolNames: List<String> = emptyList(),
    val next: AuditEventCursor? = null,
    val scanned: Int = 0,
    val exhausted: Boolean = false,
)

/** Searches only the parsed display projection. Raw payloads, tool arguments and outputs never match. */
internal object AuditHistorySearch {
    const val RESULT_LIMIT = 200
    const val SCAN_LIMIT = 1000

    @Suppress("ReturnCount") // Empty input, bounded page and exhausted scan are distinct terminal projections.
    fun find(
        filter: AuditLogFilter,
        zone: ZoneId,
        after: AuditEventCursor?,
        load: (AuditEventCursor?) -> AuditEventPage,
        checkActive: () -> Unit,
    ): AuditHistoryPage {
        val matches = mutableListOf<DispatchAuditRecord>()
        val tools = sortedSetOf<String>()
        var cursor = after
        var scanned = 0
        while (scanned < SCAN_LIMIT) {
            checkActive()
            val page = load(cursor)
            if (page.rows.isEmpty()) return AuditHistoryPage(matches, tools.toList(), null, scanned, true)
            page.rows.forEachIndexed { index, entry ->
                checkActive()
                scanned++
                val row = entry.event
                cursor = AuditEventCursor(row.timestamp, entry.cursorRowId, page.ceiling)
                val record =
                    StorageAuditSink
                        .parseRow(
                            row.id,
                            row.correlationId,
                            row.type,
                            row.actor,
                            row.redactedPayload,
                            row.timestamp,
                        )?.takeIf { it.complete }
                record?.toolName?.let(tools::add)
                if (record != null && AuditFilters.apply(record, filter, zone)) matches += record
                val end = index == page.rows.lastIndex && !page.hasMore
                if (end || matches.size == RESULT_LIMIT || scanned == SCAN_LIMIT) {
                    return AuditHistoryPage(matches, tools.toList(), cursor.takeUnless { end }, scanned, end)
                }
            }
        }
        return AuditHistoryPage(matches, tools.toList(), cursor, scanned, false)
    }
}
