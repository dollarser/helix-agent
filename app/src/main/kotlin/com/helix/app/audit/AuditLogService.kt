package com.helix.app.audit

import com.helix.app.approval.AuditLogFilter
import com.helix.app.approval.DispatchAuditRecord
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.AuditEventCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AuditHistoryStatus(
    val page: AuditHistoryPage = AuditHistoryPage(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** UI bridge: bounded keyset scans of all history, with allowlisted projection before search. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditLogService(
    private val storage: HelixStorage,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Request(
        val filter: AuditLogFilter,
        val cursor: AuditEventCursor?,
        val generation: Long,
    )

    private data class Update(
        val request: Request,
        val status: AuditHistoryStatus,
    )

    private val lock = Any()
    private val requests = MutableStateFlow(Request(AuditLogFilter(), null, 0))
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong()
    private val mutableRecords = MutableStateFlow<List<DispatchAuditRecord>>(emptyList())
    private val mutableTools = MutableStateFlow<List<String>>(emptyList())
    private val mutableStatus = MutableStateFlow(AuditHistoryStatus())
    val records: StateFlow<List<DispatchAuditRecord>> = mutableRecords.asStateFlow()
    val toolNames: StateFlow<List<String>> = mutableTools.asStateFlow()
    val history: StateFlow<AuditHistoryStatus> = mutableStatus.asStateFlow()

    init {
        scope.launch {
            requests
                .flatMapLatest { request ->
                    flow { emit(Update(request, search(request))) }
                }.collect { update ->
                    synchronized(lock) {
                        if (requests.value.generation == update.request.generation) {
                            mutableStatus.value = update.status
                            mutableRecords.value = update.status.page.records
                            mutableTools.value = update.status.page.toolNames
                        }
                    }
                }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed read is explicit failure, never an empty successful search.
    private suspend fun search(request: Request): AuditHistoryStatus =
        try {
            val context = currentCoroutineContext()
            val page =
                AuditHistorySearch.find(
                    request.filter,
                    zone,
                    request.cursor,
                    load = { storage.auditEvents.page(it) },
                    checkActive = { context.ensureActive() },
                )
            AuditHistoryStatus(page, loading = false)
        } catch (
            cancel: CancellationException,
        ) {
            throw cancel
        } catch (_: Exception) {
            AuditHistoryStatus(loading = false, failed = true)
        }

    fun setFilter(filter: AuditLogFilter) =
        synchronized(lock) {
            start(Request(filter, null, generation.incrementAndGet()))
        }

    fun nextPage() =
        synchronized(lock) {
            val status = mutableStatus.value
            val cursor = status.page.next
            if (!status.loading && !status.failed && cursor != null) {
                start(Request(requests.value.filter, cursor, generation.incrementAndGet()))
            }
        }

    fun retry() = setFilter(requests.value.filter)

    private fun start(request: Request) {
        mutableRecords.value = emptyList()
        mutableStatus.value = AuditHistoryStatus()
        requests.value = request
    }

    companion object {
        const val PAGE_LIMIT = AuditHistorySearch.RESULT_LIMIT
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
            matchesToDay(record, filter, zone) &&
            matchesKeyword(record, filter.keyword, zone)

    private fun matchesKeyword(
        record: DispatchAuditRecord,
        keyword: String,
        zone: ZoneId,
    ): Boolean {
        val wanted = keyword.trim()
        if (wanted.isEmpty()) return true
        return listOfNotNull(
            record.id,
            record.correlationId,
            record.actor,
            record.turnId,
            record.sessionId,
            record.toolName,
            record.toolVersion,
            record.code?.name,
            record.decisionSource?.name,
            record.operationClass?.name,
            dayOf(record.startedAt, zone),
        ).any { it.contains(wanted, ignoreCase = true) }
    }

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
        val wanted = filter.operationClass ?: return true
        return record.operationClass == wanted
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
