package com.helix.core.storage

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionInputMigrationDeviceTest {
    @Test
    fun v25UpgradePreservesHistoryAndDraftAndNeverQueuesLegacyText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "input-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HelixDatabase::class.java)
        try {
            helper.createDatabase(name, 25).use { db ->
                db.execSQL(
                    "INSERT INTO sessions(id,title,providerId,modelId,createdAt,archivedAt,directoryRef) " +
                        "VALUES ('old','Old',NULL,NULL,0,NULL,NULL)",
                )
                db.execSQL(
                    "INSERT INTO messages(id,sessionId,turnId,role,kind,contentRef,sequence,supersededBy) " +
                        "VALUES ('message','old',NULL,'USER','TEXT',NULL,0,NULL)",
                )
                db.execSQL("INSERT INTO composer_drafts VALUES ('old',7,'draft-id','unsent text','[]',NULL)")
            }
            helper.runMigrationsAndValidate(name, 26, true, HelixDatabase.MIGRATION_25_26).use { db ->
                db
                    .query(
                        "SELECT text,revision,delivery,expectedTurnId FROM composer_drafts WHERE sessionId='old'",
                    ).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("unsent text", it.getString(0))
                        assertEquals(7, it.getInt(1))
                        assertEquals("QUEUE", it.getString(2))
                        assertTrue(it.isNull(3))
                    }
                listOf("session_inputs", "session_input_attachments", "turns").forEach { table ->
                    assertEmptyTable(db, table)
                }
                db.query("SELECT id FROM messages").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("message", it.getString(0))
                }
            }
            val content = File(context.cacheDir, name)
            val storage = HelixStorage.open(context, name, content)
            try {
                assertEquals("unsent text", storage.composerDrafts.get("old")!!.text)
                assertTrue(storage.sessionInputs.listPending("old").isEmpty())
            } finally {
                storage.close()
                content.deleteRecursively()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun assertEmptyTable(
        db: SupportSQLiteDatabase,
        table: String,
    ) {
        db.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }
}
