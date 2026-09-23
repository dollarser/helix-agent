package com.helix.core.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.CompactManifestCodec
import com.helix.core.model.MessageRefEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RequestContextManifestDeviceTest {
    @Test
    @Suppress("NestedBlockDepth")
    fun migration27To28AddsRequestManifestColumn() {
        val name = "request-manifest-migration-${UUID.randomUUID()}.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HelixDatabase::class.java)
        try {
            helper.createDatabase(name, 27).use { db ->
                db.execSQL(
                    "INSERT INTO sessions(id,title,providerId,modelId,createdAt,archivedAt,directoryRef) " +
                        "VALUES ('s1','Session 1',NULL,NULL,0,NULL,NULL)",
                )
                db.execSQL(
                    "INSERT INTO turns(id,sessionId,clientRequestId,turnNumber,state,modelStep) " +
                        "VALUES ('t1','s1','req-01',1,'COMPLETED',1)",
                )
                db.execSQL(
                    "INSERT INTO model_calls(id,turnId,providerSnapshot,state,usage,requestId," +
                        "promptFingerprint,promptSections) VALUES ('c1','t1','{}','COMPLETED',NULL,'r1',NULL,NULL)",
                )
            }
            helper.runMigrationsAndValidate(name, 28, true, HelixDatabase.MIGRATION_27_28).use { db ->
                // Verify column was added with NULL for existing rows
                db.query("SELECT requestManifest FROM model_calls WHERE id = 'c1'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertNull(cursor.getString(0))
                }

                // Verify column accepts bounded compact manifest
                val manifest =
                    CompactManifestCodec.bounded(
                        callId = "c1",
                        timestamp = 1774300000000L,
                        checkpoint = 42L,
                        messages =
                            listOf(
                                MessageRefEntry("m1", MessageRefEntry.ROLE_USER),
                                MessageRefEntry("m2", MessageRefEntry.ROLE_ASSISTANT),
                            ),
                        inputIds = listOf("inp-001"),
                    )
                val encoded = CompactManifestCodec.encodeCompact(manifest)
                db.execSQL("UPDATE model_calls SET requestManifest = ? WHERE id = 'c1'", arrayOf(encoded))

                db.query("SELECT requestManifest FROM model_calls WHERE id = 'c1'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(encoded, cursor.getString(0))
                }

                // Verify cascade deletion
                db.execSQL("DELETE FROM sessions WHERE id = 's1'")
                db.query("SELECT COUNT(*) FROM model_calls WHERE id = 'c1'").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun boundedManifestEnforcesSingleLineLimitOnDevice() {
        val largeMessages = (1..512).map { MessageRefEntry("msg-$it", MessageRefEntry.ROLE_USER) }
        val largeInputs = (1..512).map { "input-uuid-$it" }
        val manifest =
            CompactManifestCodec.bounded(
                callId = "c-bench",
                timestamp = 1774300000000L,
                checkpoint = 100L,
                messages = largeMessages,
                inputIds = largeInputs,
            )
        val encoded = CompactManifestCodec.encodeCompact(manifest)
        val byteSize = encoded.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "Manifest byte size $byteSize exceeds 256KiB line limit",
            byteSize <= com.helix.core.model.RequestContextManifest.MAX_SINGLE_LINE_BYTES,
        )
    }
}
