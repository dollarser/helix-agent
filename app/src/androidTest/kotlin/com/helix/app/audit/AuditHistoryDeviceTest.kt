package com.helix.app.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.approval.AuditLogFilter
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.AuditEventCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.UUID

/** A private temporary Room database: no daily history or raw payload is read. */
class AuditHistoryDeviceTest {
    @Test fun tiedTimestampPagesFreezeInsertionsAndContinueAfterDeletedCursor() {
        withStorage { storage ->
            repeat(1200) { append(storage, it) }
            val first = storage.auditEvents.page(limit = 137)
            val last = first.rows.last()
            var cursor = AuditEventCursor(last.event.timestamp, last.cursorRowId, first.ceiling)
            storage.auditEvents.append("late", "late", "tool_dispatch", "dispatcher", "{}", 0)
            storage.auditEvents.deleteByCorrelations(listOf(last.event.correlationId))
            val ids = first.rows.map { it.event.id }.toMutableList()
            do {
                val page = storage.auditEvents.page(cursor, 137)
                ids += page.rows.map { it.event.id }
                val row = page.rows.last()
                cursor = AuditEventCursor(row.event.timestamp, row.cursorRowId, page.ceiling)
            } while (page.hasMore)
            assertEquals(1200, ids.size)
            assertEquals(1200, ids.toSet().size)
            assertFalse(ids.contains("late"))
        }
    }

    @Test fun serviceSearchesOlderHistoryWithoutMatchingHiddenArguments(): Unit =
        runBlocking {
            withStorage { storage ->
                repeat(1200) { append(storage, it) }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val service = AuditLogService(storage, ZoneId.of("UTC"), scope)
                try {
                    service.setFilter(AuditLogFilter(keyword = "needle"))
                    await(service)
                    assertEquals(1000, service.history.value.page.scanned)
                    assertTrue(service.records.value.isEmpty())
                    service.nextPage()
                    service.nextPage() // The duplicate click cannot advance a second time while loading.
                    await(service)
                    assertEquals(listOf("event-17"), service.records.value.map { it.id })
                    assertTrue(service.history.value.page.exhausted)
                    service.setFilter(AuditLogFilter(keyword = "HIDDEN_ARGUMENT"))
                    service.setFilter(AuditLogFilter(keyword = "time.now"))
                    await(service)
                    assertEquals(200, service.records.value.size)
                    assertTrue(service.records.value.all { it.toolName == "time.now" })
                    service.setFilter(AuditLogFilter(keyword = "HIDDEN_ARGUMENT"))
                    await(service)
                    assertTrue(service.records.value.isEmpty())
                } finally {
                    scope.cancel()
                }
            }
        }

    private suspend fun await(service: AuditLogService) =
        withTimeout(10_000) {
            while (service.history.value.loading) delay(10)
            assertFalse(service.history.value.failed)
        }

    private fun append(
        storage: HelixStorage,
        index: Int,
    ) {
        val tool = if (index == 17) "needle.read" else "time.now"
        val payload = """{"turnId":"turn-$index","sessionId":"synthetic","toolName":"$tool","toolVersion":"1",
            "code":"SUCCESS","decisionSource":"POLICY","operationClass":"READ_ONLY",
            "args":"HIDDEN_ARGUMENT","startedAt":1,"finishedAt":1}"""
        storage.auditEvents.append("event-$index", "turn-$index", "tool_dispatch", "dispatcher", payload, 1)
    }

    private inline fun withStorage(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "audit-history-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, root)
        try {
            block(storage)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
