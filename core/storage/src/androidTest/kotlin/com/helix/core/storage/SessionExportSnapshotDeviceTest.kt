package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.ApprovalEntity
import com.helix.core.storage.entity.AuditEventEntity
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.ModelCallEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.export.SessionExportRepository
import com.helix.core.storage.export.SessionExportSanitizer
import com.helix.core.storage.export.SessionExportSnapshotter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real Room relations; synthetic data only. Final content and SAF delivery have separate tests. */
class SessionExportSnapshotDeviceTest {
    @Test fun revisedHistoryRemainsExportableWithItsReplacementRequestId() =
        fixture { database, directory ->
            seed(database)
            database.messageDao().supersedeFrom("selected", 0, "replacement-request")
            assertTrue(database.messageDao().listBySession("selected").isEmpty())
            SessionExportSnapshotter(database).capture("selected", directory) {}.use { snapshot ->
                val rows = snapshot.file.readLines().map { Json.parseToJsonElement(it).jsonObject }
                val message = rows.single { it["recordId"]?.jsonPrimitive?.content == "message:message" }
                val data = message.getValue("data").jsonObject
                assertEquals("replacement-request", data.getValue("supersededBy").jsonPrimitive.content)
                assertTrue(rows.any { it["recordId"]?.jsonPrimitive?.content == "tool_result:result" })
            }
        }

    @Test fun crossSessionAndDeletedCheckpointReferencesAreMarkedWithoutExportingTheirTargets() =
        fixture { database, directory ->
            seed(database)
            val store = FileContentStore(File(directory, "bodies"))
            seedCheckpoint(database, store)
            database.turnDao().insert(TurnEntity("other-turn", "other", "COMPLETED", 0, 0, 1, null))
            database.openHelper.writableDatabase.execSQL("UPDATE messages SET turnId='other-turn' WHERE id='message'")
            database.openHelper.writableDatabase.execSQL("DELETE FROM model_calls WHERE id='model'")
            val output = ByteArrayOutputStream()
            SessionExportRepository(database, store)
                .prepare(
                    "selected",
                    directory,
                    "test",
                    SessionExportSanitizer {
                        text,
                        _,
                        ->
                        text
                    },
                    {},
                ).use {
                    it.deliver(output, {})
                }
            val rows =
                output
                    .toString("UTF-8")
                    .lineSequence()
                    .filter(String::isNotEmpty)
                    .map {
                        Json.parseToJsonElement(it).jsonObject
                    }.toList()
            assertFalse(rows.any { it["recordId"]?.jsonPrimitive?.content == "turn:other-turn" })

            fun status(
                record: String,
                field: String,
            ): String =
                rows
                    .single {
                        it["recordId"]?.jsonPrimitive?.content ==
                            record
                    }.getValue("data")
                    .jsonObject
                    .getValue("references")
                    .jsonArray
                    .single {
                        it.jsonObject["field"]?.jsonPrimitive?.content == field
                    }.jsonObject
                    .getValue("status")
                    .jsonPrimitive.content
            assertEquals("not_in_selected_snapshot", status("message:message", "turnId"))
            assertEquals("not_in_selected_snapshot", status("compaction:checkpoint", "sourceCallId"))
            assertEquals("included", status("compaction:checkpoint", "preservedMessageIds[0]"))
            assertTrue(directory.listFiles().orEmpty().none { it.isFile })
        }

    @Test fun completeProjectionPreservesHistoryCompactionAndTailWithoutProviderConfiguration() =
        fixture { database, directory ->
            seed(database)
            val store = FileContentStore(File(directory, "bodies"))
            seedCheckpoint(database, store)
            val output = ByteArrayOutputStream()
            SessionExportRepository(database, store)
                .prepare(
                    "selected",
                    directory,
                    "test",
                    SessionExportSanitizer {
                        text,
                        _,
                        ->
                        text.replace("synthetic-secret", "[redacted]")
                    },
                    {},
                ).use { it.deliver(output, {}) }
            val text = output.toString("UTF-8")
            assertFalse(text.contains("excluded-endpoint"))
            assertFalse(text.contains("synthetic-secret"))
            assertFalse(text.contains("other-session-body"))
            val rows =
                text
                    .lineSequence()
                    .filter(
                        String::isNotEmpty,
                    ).map { Json.parseToJsonElement(it).jsonObject }
                    .toList()
            assertEquals("\"header\"", rows.first()["type"].toString())
            assertEquals("\"complete\"", rows.last()["type"].toString())
            assertEquals(
                rows.size.toString(),
                rows
                    .last()
                    .getValue("data")
                    .jsonObject["recordCount"]
                    .toString(),
            )
            assertTrue(rows.any { it["recordId"].toString() == "\"message:message\"" })
            assertTrue(rows.any { it["recordId"].toString() == "\"message:checkpoint\"" })
            assertTrue(rows.any { it["recordId"].toString() == "\"compaction:checkpoint\"" })
            assertTrue(directory.listFiles().orEmpty().none { it.isFile })
            // Synthetic fixture only, consumed by the independent host parser after instrumentation.
            val context = ApplicationProvider.getApplicationContext<Context>()
            File(context.filesDir, "session-export-synthetic.jsonl").writeText(text, Charsets.UTF_8)
        }

    private fun seedCheckpoint(
        database: HelixDatabase,
        store: FileContentStore,
    ) {
        val original = store.write("original message")
        val checkpoint =
            store.write(
                """{"coveredThrough":0,"summary":"synthetic-secret summary","sourceCallId":"model",""" +
                    """"preservedMessageIds":["message"]}""",
            )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET contentRef=? WHERE id='message'",
            arrayOf(original.toStorageString()),
        )
        database.messageDao().insert(
            MessageEntity(
                "checkpoint",
                "selected",
                "turn",
                "ASSISTANT",
                "CONTEXT_CHECKPOINT_V1",
                checkpoint.toStorageString(),
                1,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE model_calls SET providerSnapshot=? WHERE id='model'",
            arrayOf("""{"model":"fixture","endpoint":"https://excluded-endpoint.invalid"}"""),
        )
    }

    @Test fun contentReferencesAreDeduplicatedAcrossMessagesAndResultsWithoutReadingBodies() =
        fixture { database, directory ->
            seed(database)
            val hash = "a".repeat(64)
            val ref = ContentRef(ContentRef.expectedPath(hash), 42, hash).toStorageString()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE messages SET contentRef=? WHERE id='message'",
                arrayOf(ref),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE tool_results SET contentRef=? WHERE id='result'",
                arrayOf(ref),
            )
            SessionExportSnapshotter(database).capture("selected", directory) {}.use { snapshot ->
                val rows = snapshot.file.readLines().map { Json.parseToJsonElement(it).jsonObject }
                val content = rows.filter { it["type"].toString() == "\"content\"" }
                assertEquals(1, content.size)
                assertEquals("\"content:$hash\"", content.single().getValue("recordId").toString())
                assertEquals(
                    ref,
                    content
                        .single()
                        .getValue("data")
                        .jsonObject
                        .getValue("contentRef")
                        .jsonPrimitive.content,
                )
            }
        }

    @Test fun isolatedProjectionPreservesIdsNullUsageAndExplicitOversizedArguments() =
        fixture { database, directory ->
            seed(database)
            SessionExportSnapshotter(database).capture("selected", directory) {}.use { snapshot ->
                val text = snapshot.file.readText()
                assertFalse(text.contains("other-session-body"))
                assertFalse(text.contains("synthetic-approval-binding"))
                val rows =
                    text
                        .lineSequence()
                        .filter(
                            String::isNotEmpty,
                        ).map { Json.parseToJsonElement(it).jsonObject }
                        .toList()
                val call = rows.single { it["recordId"].toString() == "\"tool_call:tool\"" }
                assertEquals(JsonNull, call.getValue("data").jsonObject["argsJson"])
                assertEquals("200000", call.getValue("omittedFields").jsonObject["argsJson"].toString())
                val model = rows.single { it["recordId"].toString() == "\"model_call:model\"" }
                assertEquals(JsonNull, model.getValue("data").jsonObject["usage"])
                assertTrue(rows.any { it["recordId"].toString() == "\"approval:approval\"" })
                assertTrue(rows.any { it["recordId"].toString() == "\"tool_result:result\"" })
                assertTrue(rows.any { it["recordId"].toString() == "\"usage:audit:budget\"" })
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }

    @Test fun cancellationAndMissingSessionLeaveNoSnapshot() =
        fixture { database, directory ->
            seed(database)
            var checks = 0
            assertThrows(CancellationException::class.java) {
                SessionExportSnapshotter(database).capture("selected", directory) {
                    if (++checks == 10) throw CancellationException("synthetic cancel")
                }
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertThrows(IllegalStateException::class.java) {
                SessionExportSnapshotter(database).capture("absent", directory) {}
            }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }

    @Test fun concurrentMutationCannotMixOldMessagesWithNewTurnState() =
        fixture { database, directory ->
            seed(database)
            val attempt = CountDownLatch(1)
            val finished = CountDownLatch(1)
            var writer: Thread? = null
            var writerFailure: Throwable? = null
            val snapshot =
                SessionExportSnapshotter(database).capture("selected", directory) {
                    if (writer == null) {
                        writer =
                            thread {
                                try {
                                    attempt.countDown()
                                    database.runInTransaction {
                                        database.openHelper.writableDatabase.execSQL(
                                            "UPDATE turns SET state='COMPLETED' WHERE id='turn'",
                                        )
                                        database.openHelper.writableDatabase.execSQL(
                                            "DELETE FROM messages WHERE sessionId='selected'",
                                        )
                                    }
                                } catch (failure: Throwable) {
                                    writerFailure = failure
                                } finally {
                                    finished.countDown()
                                }
                            }
                        check(attempt.await(5, TimeUnit.SECONDS))
                    }
                }
            snapshot.use {
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                writer?.join(1000)
                writerFailure?.let { throw AssertionError("Concurrent writer failed", it) }
                val text = snapshot.file.readText()
                assertTrue(text.contains("\"state\":\"RUNNING_TOOL\""))
                assertTrue(text.contains("\"recordId\":\"message:message\""))
                assertTrue(database.messageDao().listBySession("selected").isEmpty())
                assertEquals("COMPLETED", database.turnDao().byId("turn")?.state)
            }
        }

    private fun seed(database: HelixDatabase) {
        database.auditEventDao().append(AuditEventEntity("budget", "model", "budget.request", "agent", "{}", 1))
        database.sessionDao().insert(SessionEntity("selected", "title", null, null, 1, null))
        database.sessionDao().insert(SessionEntity("other", "other-session-body", null, null, 1, null))
        database.turnDao().insert(TurnEntity("turn", "selected", "RUNNING_TOOL", 1, 1, null, null))
        database.messageDao().insert(MessageEntity("message", "selected", "turn", "USER", "TEXT", null, 0))
        database.modelCallDao().insert(ModelCallEntity("model", "turn", "{}", "COMPLETED", null, null))
        database.toolCallDao().insert(
            ToolCallEntity("tool", "turn", "call", "read", "1", "x".repeat(200000), "hash", "RUNNING"),
        )
        database.toolResultDao().insert(ToolResultEntity("result", "tool", "UNKNOWN", "unknown", null, false))
        database.approvalDao().insert(
            ApprovalEntity("approval", "tool", "synthetic-approval-binding", null, null, null, 10),
        )
    }

    private fun fixture(block: (HelixDatabase, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "export-test-${UUID.randomUUID()}")
        val database = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            block(database, directory)
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
