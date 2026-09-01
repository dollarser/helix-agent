package com.helix.app.audit

import com.helix.app.approval.AuditLogFilter
import com.helix.app.approval.DispatchAuditRecord
import com.helix.app.approval.StorageAuditSink
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The UI bridge for the audit log page (roadmap HXA-036): the only app-level reader of
 * `audit_events` for the page. The UI never touches the DAO (AGENTS.md); it observes this
 * service.
 *
 * Guarantees:
 * - BOUNDED LOAD: [records] reads at most [PAGE_LIMIT] newest rows (security doc section
 *   10: the page never loads the whole table) and applies the filter in memory over that
 *   page; a larger history is reached by paging, not by an unbounded query.
 * - REDACTED ONLY: rows are parsed through the [StorageAuditSink.PAYLOAD_KEYS] allowlist
 *   into [DispatchAuditRecord] — a type with no slot for argument or output content.
 *   Rows that are not tool-dispatch events, or whose payload is malformed, are dropped
 *   (fail closed: hidden, never rendered raw).
 * - COMPLETE ONLY: a record must have parsed every mandatory display fact (turn, session,
 *   tool, code, decision source) or it is hidden — a half-parsed row is not an audit row.
 * - MAIN-THREAD SAFE: the page's Room read runs on an IO scope and republishes StateFlows;
 *   the UI only observes those flows (a synchronous Room read in composition would crash).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditLogService(
    private val storage: HelixStorage,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    // The screen pushes its current filter on EVERY composition (page shown, filter
    // changed). A bare StateFlow would CONFLATE equal filters — the first push after new
    // audit rows landed would be a no-op and the page would keep showing the startup
    // snapshot. The generation counter makes every push a fresh load request.
    private val filterState = MutableStateFlow<Pair<AuditLogFilter, Long>>(AuditLogFilter() to 0L)

    // Atomic (M3 closeout review): the counter is bumped on the main thread while the
    // collector thread reads the pair it published — a torn read of a plain Long field
    // would let two different filters share one generation and one page win by chance.
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0L)

    private val _records = MutableStateFlow<List<DispatchAuditRecord>>(emptyList())

    /** The newest bounded page matching the current filter — loaded OFF the main thread. */
    val records: StateFlow<List<DispatchAuditRecord>> = _records.asStateFlow()

    private val _toolNames = MutableStateFlow<List<String>>(emptyList())

    /** The distinct tool names on the current page (the unfiltered pick list). */
    val toolNames: StateFlow<List<String>> = _toolNames.asStateFlow()

    init {
        scope.launch {
            // One bounded Room read per load request (on IO); the pick list stays
            // UNFILTERED (switching the tool filter must not hide its own options).
            filterState
                .flatMapLatest { (filter, _) ->
                    flow {
                        val page = loadPage()
                        emit(page to page.filter { AuditFilters.apply(it, filter, zone) })
                    }
                }.collect { (page, filtered) ->
                    _toolNames.value = page.mapNotNull { it.toolName }.distinct().sorted()
                    _records.value = filtered
                }
        }
    }

    /** Changes the page filter (or re-requests the current one); the page reloads on IO
     * and republishes [records] + [toolNames]. */
    fun setFilter(filter: AuditLogFilter) {
        filterState.value = filter to generation.incrementAndGet()
    }

    /** The newest [limit] COMPLETE tool-dispatch records on the page, unfiltered (the
     * synchronous core: one bounded Room read + allowlist parse; the page applies the
     * filter in memory over that page). */
    fun loadPage(limit: Int = PAGE_LIMIT): List<DispatchAuditRecord> =
        storage.auditEvents
            .recent(limit)
            .mapNotNull { row ->
                StorageAuditSink.parseRow(
                    id = row.id,
                    correlationId = row.correlationId,
                    type = row.type,
                    actor = row.actor,
                    redactedPayload = row.redactedPayload,
                    timestamp = row.timestamp,
                )
            }.filter { it.complete }

    /** One filtered page, synchronously (JVM tests; never the main thread in production). */
    fun records(
        filter: AuditLogFilter,
        limit: Int = PAGE_LIMIT,
    ): List<DispatchAuditRecord> = loadPage(limit).filter { AuditFilters.apply(it, filter, zone) }

    /** The distinct tool names present in the current page (the filter's pick list). */
    fun toolNames(limit: Int = PAGE_LIMIT): List<String> =
        loadPage(limit)
            .mapNotNull { it.toolName }
            .distinct()
            .sorted()

    companion object {
        /** One page of the audit list (newest first). */
        const val PAGE_LIMIT = 200
    }
}

/**
 * The pure audit-page filter (roadmap HXA-036: 会话、工具、风险、日期). Extracted as a
 * testable unit: each present predicate narrows, absent predicates are wildcards, and a
 * record that lacks the field a predicate targets never matches (fail closed).
 */
object AuditFilters {
    fun apply(
        record: DispatchAuditRecord,
        filter: AuditLogFilter,
        zone: ZoneId,
    ): Boolean =
        matchesSession(record, filter) &&
            matchesTool(record, filter) &&
            matchesRisk(record, filter) &&
            matchesFromDay(record, filter, zone) &&
            matchesToDay(record, filter, zone)

    /** Applies [filter] to a page, preserving order (newest first). */
    fun applyAll(
        records: List<DispatchAuditRecord>,
        filter: AuditLogFilter,
        zone: ZoneId,
    ): List<DispatchAuditRecord> = records.filter { apply(it, filter, zone) }

    // One predicate per filterable field: an ABSENT predicate is a wildcard (matches
    // everything); a record that LACKS the targeted field never matches (fail closed).

    private fun matchesSession(
        record: DispatchAuditRecord,
        filter: AuditLogFilter,
    ): Boolean {
        val wanted = filter.sessionId ?: return true
        return record.sessionId == wanted
    }

    private fun matchesTool(
        record: DispatchAuditRecord,
        filter: AuditLogFilter,
    ): Boolean {
        val wanted = filter.toolName ?: return true
        return record.toolName == wanted
    }

    private fun matchesRisk(
        record: DispatchAuditRecord,
        filter: AuditLogFilter,
    ): Boolean {
        val wanted = filter.risk ?: return true
        return record.risk == wanted
    }

    private fun matchesFromDay(
        record: DispatchAuditRecord,
        filter: AuditLogFilter,
        zone: ZoneId,
    ): Boolean {
        val from = filter.fromDay ?: return true
        return dayOf(record.startedAt, zone) >= from
    }

    private fun matchesToDay(
        record: DispatchAuditRecord,
        filter: AuditLogFilter,
        zone: ZoneId,
    ): Boolean {
        val to = filter.toDay ?: return true
        return dayOf(record.startedAt, zone) <= to
    }
}

/** The ISO `yyyy-MM-dd` day of an epoch-millis timestamp in [zone] (string-compared in [apply]). */
internal fun dayOf(
    epochMillis: Long,
    zone: ZoneId,
): String =
    Instant
        .ofEpochMilli(epochMillis)
        .atZone(zone)
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
