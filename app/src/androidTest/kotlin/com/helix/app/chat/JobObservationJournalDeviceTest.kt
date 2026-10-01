package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.JobObservation
import com.helix.tools.framework.JobObservationBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Room journal and reopen; no Runtime, model or device command is executed by the fixture itself. */
class JobObservationJournalDeviceTest {
    @Test fun repeatedSnapshotsDeduplicateAndForkDoesNotInheritJournal() =
        fixture { storage ->
            val journal = JobObservationJournal(storage)
            journal.record(value())
            journal.record(value().copy(observedAtMillis = 99))
            assertEquals(1, storage.auditEvents.recentByCorrelation("s", JobObservationJournal.TYPE, 64).size)
            assertTrue(storage.auditEvents.recentByCorrelation("fork", JobObservationJournal.TYPE, 64).isEmpty())
        }

    @Test fun publicationOrderSurvivesClockRollbackAndSettlementIsDistinct() =
        fixture { storage ->
            val journal = JobObservationJournal(storage)
            journal.record(value().copy(observedAtMillis = 100))
            val terminal =
                value().copy(
                    state = "SUCCEEDED",
                    terminal = true,
                    revision = "terminal",
                    observedAtMillis = 10,
                )
            journal.record(terminal)
            journal.record(terminal.copy(settlementPending = false))
            val rows = storage.auditEvents.recentByCorrelation("s", JobObservationJournal.TYPE, 64)
            assertEquals(3, rows.size)
            val last = Json.parseToJsonElement(rows.first().redactedPayload).jsonObject
            assertEquals("false", last.getValue("settlementPending").jsonPrimitive.content)
        }

    @Test fun reopeningTheDatabasePreservesOriginalObservationIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "job-observation-${UUID.randomUUID()}.db"
        val files = File(context.filesDir, name)
        try {
            withStorage(context, name, files) { JobObservationJournal(it).record(value()) }
            withStorage(context, name, files) { storage ->
                val row = storage.auditEvents.recentByCorrelation("s", JobObservationJournal.TYPE, 64).single()
                assertEquals("s", row.correlationId)
                JobObservationJournal(storage).record(value())
                assertEquals(1, storage.auditEvents.recentByCorrelation("s", JobObservationJournal.TYPE, 64).size)
            }
        } finally {
            context.deleteDatabase(name)
            files.deleteRecursively()
        }
    }

    private fun withStorage(
        context: Context,
        name: String,
        files: File,
        block: (HelixStorage) -> Unit,
    ) {
        val storage = HelixStorage.open(context, name, files)
        try {
            block(storage)
        } finally {
            storage.close()
        }
    }

    private fun value() =
        JobObservation(
            JobObservationBinding("s", "t", "c", "p", "e", "g", "a".repeat(64)),
            "RUNNING",
            false,
            false,
            true,
            "r",
            1,
        )

    private fun fixture(block: (HelixStorage) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "job-observation-${UUID.randomUUID()}.db"
        val files = File(context.filesDir, name)
        try {
            withStorage(context, name, files, block)
        } finally {
            context.deleteDatabase(name)
            files.deleteRecursively()
        }
    }
}
