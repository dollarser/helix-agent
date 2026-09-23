package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.CompactManifestCodec
import com.helix.core.model.MessageRefEntry
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.ModelCallEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.export.SessionExportFormat
import com.helix.core.storage.export.SessionExportSnapshotter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class RequestManifestExportDeviceTest {
    @Test
    fun exportProjectsRequestManifestInModelCallRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "export-manifest-test-${UUID.randomUUID()}")
        val database = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            database.sessionDao().insert(SessionEntity("s1", "Session 1", null, null, 1, null))
            database.turnDao().insert(TurnEntity("t1", "s1", "req-1", 1, 1, null, null))
            database.messageDao().insert(MessageEntity("m1", "s1", "t1", "USER", "TEXT", null, 0))
            database.messageDao().insert(MessageEntity("m2", "s1", "t1", "ASSISTANT", "TEXT", null, 1))

            val manifest =
                CompactManifestCodec.bounded(
                    callId = "c1",
                    timestamp = 1774300000000L,
                    checkpoint = 10L,
                    messages =
                        listOf(
                            MessageRefEntry("m1", MessageRefEntry.ROLE_USER),
                            MessageRefEntry("m2", MessageRefEntry.ROLE_ASSISTANT),
                        ),
                    inputIds = listOf("inp-001", "inp-002"),
                )
            val manifestJson = CompactManifestCodec.encodeCompact(manifest)

            database.modelCallDao().insert(
                ModelCallEntity(
                    id = "c1",
                    turnId = "t1",
                    providerSnapshot = "{\"model\":\"gpt-test\"}",
                    state = "COMPLETED",
                    usage = null,
                    requestId = "req-c1",
                    promptFingerprint = "fp-123",
                    promptSections = "[\"env\"]",
                    requestManifest = manifestJson,
                ),
            )

            SessionExportSnapshotter(database).capture("s1", directory) {}.use { snapshot ->
                val lines = snapshot.file.readLines()
                // All lines must be <= LINE_BYTES
                for (line in lines) {
                    assertTrue(
                        "Line bytes must not exceed ${SessionExportFormat.LINE_BYTES}",
                        line.toByteArray(Charsets.UTF_8).size <= SessionExportFormat.LINE_BYTES,
                    )
                }

                val rows = lines.map { Json.parseToJsonElement(it).jsonObject }
                val modelRow = rows.single { it["recordId"]?.jsonPrimitive?.content == "model_call:c1" }
                val data = modelRow.getValue("data").jsonObject
                val exportedManifest = data.getValue("requestManifest").jsonPrimitive.content
                assertEquals(manifestJson, exportedManifest)

                // Verify zero token / secret / wire leak in JSONL
                for (line in lines) {
                    assertFalse(line.contains("sk-"))
                    assertFalse(line.contains("Bearer "))
                    assertFalse(line.contains("Authorization"))
                }
            }
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyModelCallWithoutManifestExportsNullField() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "export-legacy-test-${UUID.randomUUID()}")
        val database = Room.inMemoryDatabaseBuilder(context, HelixDatabase::class.java).build()
        try {
            database.sessionDao().insert(SessionEntity("s2", "Session 2", null, null, 1, null))
            database.turnDao().insert(TurnEntity("t2", "s2", "req-2", 1, 1, null, null))
            database.modelCallDao().insert(
                ModelCallEntity(
                    id = "c2",
                    turnId = "t2",
                    providerSnapshot = "{}",
                    state = "COMPLETED",
                    usage = null,
                    requestId = "r2",
                    requestManifest = null,
                ),
            )

            SessionExportSnapshotter(database).capture("s2", directory) {}.use { snapshot ->
                val rows = snapshot.file.readLines().map { Json.parseToJsonElement(it).jsonObject }
                val modelRow = rows.single { it["recordId"]?.jsonPrimitive?.content == "model_call:c2" }
                val data = modelRow.getValue("data").jsonObject
                assertEquals(JsonNull, data["requestManifest"])
            }
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
