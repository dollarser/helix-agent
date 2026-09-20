package com.helix.core.storage

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.export.SessionExportFormat
import com.helix.core.storage.export.SessionExportQuery
import com.helix.core.storage.export.SessionExportRepository
import com.helix.core.storage.export.SessionExportSanitizer
import com.helix.core.storage.export.SessionExportSnapshotter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** On-disk synthetic databases; reports observed peaks, not a claim about all OEM memory policies. */
class SessionExportResourceDeviceTest {
    @Test fun largeRelationSetStreamsWithinDiskCapsAndRecordsPeakMemory() =
        fixture { database, directory, context ->
            seed(database, messages = 10000, argumentBytes = 24 * 1024)
            saveQueryPlans(database, context)
            val temporary = File(directory, "export")
            val probe = ExportResourceProbe(temporary)
            val repository = SessionExportRepository(database, FileContentStore(File(directory, "bodies")))
            val target = File(directory, "delivered.jsonl")
            repository
                .prepare(
                    "selected",
                    temporary,
                    "resource-fixture",
                    SessionExportSanitizer { text, _ -> text },
                    probe::sample,
                ).use {
                    assertTrue(it.size > 64L * 1024 * 1024)
                    assertTrue(it.size <= SessionExportFormat.FILE_BYTES)
                    it.deliver(target.outputStream(), probe::sample)
                    assertTrue(target.length() == it.size)
                }
            assertRecordCounts(target)
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
            probe.write(File(context.filesDir, "session-export-resource.json"), target.length())
        }

    @Test fun actualDefaultSnapshotLimitFailsAndCleansOwnedTemporaryData() =
        fixture { database, directory, context ->
            seed(database, messages = 0, argumentBytes = 48 * 1024)
            val temporary = File(directory, "export")
            val probe = ExportResourceProbe(temporary)
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    SessionExportSnapshotter(database).capture("selected", temporary, probe::sample).close()
                }
            assertTrue(failure.message.orEmpty().contains("limit"))
            assertTrue(temporary.listFiles().orEmpty().isEmpty())
            assertTrue(database.toolCallDao().byId("tool-2999") != null)
            probe.write(File(context.filesDir, "session-export-snapshot-limit.json"), 0)
        }

    private fun assertRecordCounts(target: File) {
        val tail =
            RandomAccessFile(target, "r").use { file ->
                val bytes = ByteArray(minOf(8192L, file.length()).toInt())
                file.seek(file.length() - bytes.size)
                file.readFully(bytes)
                bytes.toString(Charsets.UTF_8).lineSequence().last { it.isNotEmpty() }
            }
        val counts =
            Json
                .parseToJsonElement(tail)
                .jsonObject
                .getValue("data")
                .jsonObject
                .getValue("counts")
                .jsonObject
        org.junit.Assert.assertEquals(10000, counts.getValue("message").jsonPrimitive.int)
        org.junit.Assert.assertEquals(3000, counts.getValue("tool_call").jsonPrimitive.int)
    }

    private fun saveQueryPlans(
        database: HelixDatabase,
        context: Context,
    ) {
        val query = SessionExportQuery.ALL.single { it.from == "tool_calls x" }
        val sql = query.rowSql().substringBefore(" WHERE ") + " WHERE ${query.predicate} ORDER BY ${query.order}"
        val variants =
            listOf(
                "keys" to query.keysSql(false),
                "bulk" to sql,
                "before" to sql.replaceFirst("SELECT ", "SELECT DISTINCT "),
            )
        val plans =
            buildJsonObject {
                variants.forEach { (name, query) ->
                    put(
                        name,
                        buildJsonArray {
                            val result =
                                database.openHelper.readableDatabase.query(
                                    "EXPLAIN QUERY PLAN $query",
                                    arrayOf("selected"),
                                )
                            result.use { cursor ->
                                while (cursor.moveToNext()) add(cursor.getString(3))
                            }
                        },
                    )
                }
            }
        File(context.filesDir, "session-export-query-plans.json").writeText(plans.toString())
    }

    private fun seed(
        database: HelixDatabase,
        messages: Int,
        argumentBytes: Int,
    ) {
        val arguments = "{\"body\":\"" + "x".repeat(argumentBytes) + "\"}"
        database.runInTransaction {
            database.sessionDao().insert(SessionEntity("selected", "resource fixture", null, null, 1, null))
            database.turnDao().insert(TurnEntity("turn", "selected", "COMPLETED", 1, 1, 2, null))
            repeat(messages) { index ->
                database.messageDao().insert(
                    MessageEntity("message-$index", "selected", "turn", "USER", "TEXT", null, index.toLong()),
                )
            }
            repeat(3000) { index ->
                database.toolCallDao().insert(
                    ToolCallEntity(
                        "tool-$index",
                        "turn",
                        "call-$index",
                        "read",
                        "1",
                        arguments,
                        "fixture",
                        "COMPLETED",
                    ),
                )
            }
        }
    }

    private fun fixture(block: (HelixDatabase, File, Context) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "export-resource-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, id)
        val database = Room.databaseBuilder(context, HelixDatabase::class.java, id).build()
        try {
            block(database, directory, context)
        } finally {
            database.close()
            context.deleteDatabase(id)
            directory.deleteRecursively()
        }
    }
}

private class ExportResourceProbe(
    private val directory: File,
) {
    private val started = SystemClock.elapsedRealtime()
    private val baselineHeap = heap()
    private val baselinePss = Debug.getPss()
    private var peakHeap = baselineHeap
    private var peakPss = baselinePss
    private var peakTemporary = 0L
    private var nextSample = 0L
    private var samples = 0

    fun sample() {
        val now = SystemClock.elapsedRealtime()
        if (now < nextSample) return
        nextSample = now + 100
        samples++
        peakHeap = maxOf(peakHeap, heap())
        peakPss = maxOf(peakPss, Debug.getPss())
        peakTemporary = maxOf(peakTemporary, directory.listFiles().orEmpty().sumOf { it.length() })
        check(peakTemporary <= 2 * SessionExportFormat.FILE_BYTES) { "Combined export staging exceeded cap" }
    }

    fun write(
        file: File,
        delivered: Long,
    ) {
        val report =
            buildJsonObject {
                put("deliveredBytes", delivered)
                put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                put("samples", samples)
                put("samplingIntervalMillis", 100)
                put("baselineHeapBytes", baselineHeap)
                put("peakHeapBytes", peakHeap)
                put("baselinePssKiB", baselinePss)
                put("peakPssKiB", peakPss)
                put("peakTemporaryBytes", peakTemporary)
            }
        file.writeText(report.toString())
    }

    private fun heap(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
}
