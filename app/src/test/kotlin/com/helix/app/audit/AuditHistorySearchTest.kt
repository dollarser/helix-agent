package com.helix.app.audit

import com.helix.app.approval.AuditLogFilter
import com.helix.core.storage.dao.AuditEventPageRow
import com.helix.core.storage.entity.AuditEventEntity
import com.helix.core.storage.repository.AuditEventCursor
import com.helix.core.storage.repository.AuditEventPage
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

class AuditHistorySearchTest {
    private val zone = ZoneId.of("UTC")

    private fun rows(
        count: Int,
        marker: Int = 17,
    ): List<AuditEventPageRow> =
        (count downTo 1).map { number ->
            val name = if (number == marker) "needle42.read" else "time.now"
            val payload = """{"turnId":"turn-$number","sessionId":"session","toolName":"$name", "toolVersion":"1",
            "code":"SUCCESS","decisionSource":"POLICY","operationClass":"READ_ONLY",
            "args":"FORBIDDEN_MARKER","startedAt":$number,"finishedAt":$number}"""
            AuditEventPageRow(
                AuditEventEntity(
                    "event-$number",
                    "turn-$number",
                    "tool_dispatch",
                    "dispatcher",
                    payload,
                    number.toLong(),
                ),
                number.toLong(),
            )
        }

    private fun loader(
        rows: List<AuditEventPageRow>,
        batch: Int = 70,
    ) = { cursor: AuditEventCursor? ->
        val ceiling = cursor?.ceiling ?: rows.maxOfOrNull { it.cursorRowId } ?: 0
        val eligible = rows.filter { it.cursorRowId <= ceiling && (cursor == null || it.cursorRowId < cursor.rowId) }
        AuditEventPage(eligible.take(batch), ceiling, eligible.size > batch)
    }

    @Test fun oldMatchesRemainReachableAfterAnEmptyBoundedBatch() {
        val load = loader(rows(2500))
        val first = AuditHistorySearch.find(AuditLogFilter(keyword = "NEEDLE42"), zone, null, load) {}
        assertTrue(first.records.isEmpty())
        assertEquals(1000, first.scanned)
        assertFalse(first.exhausted)
        assertNotNull(first.next)
        val second = AuditHistorySearch.find(AuditLogFilter(keyword = "needle42"), zone, first.next, load) {}
        val last = AuditHistorySearch.find(AuditLogFilter(keyword = "needle42"), zone, second.next, load) {}
        assertTrue(last.exhausted)
        assertEquals(listOf("event-17"), last.records.map { it.id })
    }

    @Test fun fillingAResultPageMidSourceBatchDoesNotSkipAnyRows() {
        val source = rows(450)
        val load = loader(source)
        val first = AuditHistorySearch.find(AuditLogFilter(), zone, null, load) {}
        val second = AuditHistorySearch.find(AuditLogFilter(), zone, first.next, load) {}
        val third = AuditHistorySearch.find(AuditLogFilter(), zone, second.next, load) {}
        assertEquals(listOf(200, 200, 50), listOf(first, second, third).map { it.records.size })
        assertEquals(source.map { it.event.id }, (first.records + second.records + third.records).map { it.id })
        assertTrue(third.exhausted)
    }

    @Test fun hiddenPayloadFieldsNeverMatchAndVisibleFiltersStillIntersect() {
        val load = loader(rows(20))
        val hidden = AuditHistorySearch.find(AuditLogFilter(keyword = "FORBIDDEN_MARKER"), zone, null, load) {}
        assertTrue(hidden.exhausted)
        assertTrue(hidden.records.isEmpty())
        val wrongSession =
            AuditHistorySearch.find(
                AuditLogFilter(sessionId = "other", keyword = "needle42"),
                zone,
                null,
                load,
            ) {}
        assertTrue(wrongSession.records.isEmpty())
        val visible = AuditHistorySearch.find(AuditLogFilter(keyword = "  success  "), zone, null, load) {}
        assertEquals(20, visible.records.size)
    }

    @Test fun cancellationAndReadFailureAreNotSuccessfulEmptyHistory() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            AuditHistorySearch.find(AuditLogFilter(keyword = "missing"), zone, null, loader(rows(1000))) {
                if (++checks == 99) throw CancellationException("Synthetic cancellation")
            }
        }
        assertEquals(99, checks)
        assertThrows(IOException::class.java) {
            AuditHistorySearch.find(AuditLogFilter(), zone, null, { throw IOException("Synthetic read failure") }) {}
        }
    }
}
