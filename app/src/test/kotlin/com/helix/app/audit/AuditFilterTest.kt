package com.helix.app.audit

import com.helix.app.approval.AuditLogFilter
import com.helix.app.approval.DispatchAuditRecord
import com.helix.core.model.RiskLevel
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchOutcomeCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * HXA-036 (app): the audit page filter (会话、工具、风险、日期) as a pure function —
 * present predicates narrow, absent ones are wildcards, and records that lack a field a
 * predicate targets never match (fail closed).
 */
class AuditFilterTest {
    private val zone = ZoneId.of("UTC")

    private fun record(
        id: String,
        sessionId: String = "s-1",
        toolName: String = "time.now",
        risk: RiskLevel? = RiskLevel.L0,
        startedAt: Long = 1_700_000_000_000L, // 2023-11-14T22:13:20Z
    ) =
        DispatchAuditRecord(
            id = id,
            correlationId = "t-$id",
            actor = "dispatcher",
            turnId = "t-$id",
            sessionId = sessionId,
            toolName = toolName,
            toolVersion = "1",
            code = DispatchOutcomeCode.SUCCESS,
            decisionSource = DecisionSource.POLICY,
            risk = risk,
            startedAt = startedAt,
            finishedAt = startedAt + 5L,
        )

    @Test
    fun emptyFilterMatchesEverything() {
        val records = listOf(record("a"), record("b"), record("c"))
        assertEquals(3, AuditFilters.applyAll(records, AuditLogFilter(), zone).size)
    }

    @Test
    fun sessionFilter() {
        val records = listOf(record("a", sessionId = "s-1"), record("b", sessionId = "s-2"))
        val result = AuditFilters.applyAll(records, AuditLogFilter(sessionId = "s-2"), zone)
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun toolFilter() {
        val records = listOf(record("a", toolName = "time.now"), record("b", toolName = "fs.write"))
        val result = AuditFilters.applyAll(records, AuditLogFilter(toolName = "fs.write"), zone)
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun riskFilterNeverMatchesRecordsWithoutRisk() {
        val records = listOf(record("a", risk = RiskLevel.L2), record("b", risk = null))
        val result = AuditFilters.applyAll(records, AuditLogFilter(risk = RiskLevel.L2), zone)
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun dateFilterIsInclusiveOnBothBounds() {
        // 2023-11-14T22:13:20Z in UTC = day 2023-11-14; in +12:00 it is 2023-11-15.
        val day = 1_700_000_000_000L
        val rec = record("a", startedAt = day)
        assertTrue(AuditFilters.apply(rec, AuditLogFilter(fromDay = "2023-11-14"), zone))
        assertTrue(AuditFilters.apply(rec, AuditLogFilter(toDay = "2023-11-14"), zone))
        assertTrue(AuditFilters.apply(rec, AuditLogFilter(fromDay = "2023-11-14", toDay = "2023-11-14"), zone))
        assertFalse(AuditFilters.apply(rec, AuditLogFilter(fromDay = "2023-11-15"), zone))
        assertFalse(AuditFilters.apply(rec, AuditLogFilter(toDay = "2023-11-13"), zone))
    }

    @Test
    fun dateFilterHonorsTheZone() {
        val rec = record("a", startedAt = 1_700_000_000_000L)
        // Same instant, +12:00 zone -> the next day: the UTC-day filter must NOT match.
        assertFalse(AuditFilters.apply(rec, AuditLogFilter(toDay = "2023-11-14"), ZoneId.of("Pacific/Auckland")))
        assertTrue(AuditFilters.apply(rec, AuditLogFilter(toDay = "2023-11-15"), ZoneId.of("Pacific/Auckland")))
    }

    @Test
    fun predicatesCombineWithAnd() {
        val records =
            listOf(
                record("a", sessionId = "s-1", toolName = "fs.write", risk = RiskLevel.L2),
                record("b", sessionId = "s-1", toolName = "fs.write", risk = RiskLevel.L0),
                record("c", sessionId = "s-2", toolName = "fs.write", risk = RiskLevel.L2),
            )
        val result =
            AuditFilters.applyAll(
                records,
                AuditLogFilter(sessionId = "s-1", toolName = "fs.write", risk = RiskLevel.L2),
                zone,
            )
        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun fromUiTreatsBlankDateInputsAsNoBound() {
        // The page passes the raw text fields: blank = "no bound" (wildcard).
        assertEquals(AuditLogFilter(), AuditLogFilter.fromUi(null, null, null, "", ""))
        val filter = AuditLogFilter.fromUi("s-1", null, RiskLevel.L2, "2023-11-14", "   ")
        assertFalse(filter.isEmpty)
        assertEquals("s-1", filter.sessionId)
        assertEquals(RiskLevel.L2, filter.risk)
        assertEquals("2023-11-14", filter.fromDay)
        assertEquals(null, filter.toDay)
    }
}
