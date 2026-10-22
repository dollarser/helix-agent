package com.helix.app.chat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.PreviewDatabaseResetRequired
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Only a uniquely named synthetic database; never the application's normal database or files. */
class RetiredGoalCounterDeviceTest {
    @Test fun freshSchemaOmitsCounterAndOldBaselineWaitsForConfirmation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "retired-goal-${UUID.randomUUID()}.db"
        val databasePath = context.getDatabasePath(name).path
        val content = File(context.cacheDir, name).apply { check(mkdirs()) }
        val retained = File(content, "retained.txt").apply { writeText("synthetic file") }
        try {
            withStorage(context, name, content) { storage ->
                storage.sessions.create("synthetic-session", "Retained before confirmation", null, null, 1)
            }
            SQLiteDatabase
                .openDatabase(
                    databasePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { db ->
                    assertFalse(goalColumns(db).contains("retries"))
                    db.execSQL("ALTER TABLE goals ADD COLUMN retries INTEGER NOT NULL DEFAULT 0")
                    val oldIdentity = "synthetic-retired-goal-schema"
                    db.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf(oldIdentity))
                }
            val reset =
                assertThrows(PreviewDatabaseResetRequired::class.java) {
                    HelixStorage.open(context, name, content).close()
                }
            SQLiteDatabase
                .openDatabase(
                    databasePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    db.rawQuery("SELECT COUNT(*) FROM sessions WHERE id = 'synthetic-session'", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(1, it.getInt(0))
                    }
                }
            reset.confirm() // Explicitly reset only the owned synthetic database after checking it was untouched.
            withStorage(context, name, content) { storage ->
                assertTrue(storage.sessions.list().isEmpty())
            }
            assertEquals("synthetic file", retained.readText())
        } finally {
            context.deleteDatabase(name)
            content.deleteRecursively()
        }
    }

    private fun goalColumns(db: SQLiteDatabase): List<String> =
        db.rawQuery("PRAGMA table_info(goals)", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun withStorage(
        context: Context,
        name: String,
        content: File,
        read: (HelixStorage) -> Unit,
    ) {
        val storage = HelixStorage.open(context, name, content)
        try {
            read(storage)
        } finally {
            storage.close()
        }
    }
}
