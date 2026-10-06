package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime proof that a fresh install creates exactly the current v1 baseline. */
@RunWith(AndroidJUnit4::class)
class FreshSchemaDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun incompatibleDevelopmentSchemaIsRebuiltButFilesAndCompatibleReopensSurvive() {
        val file = java.io.File(context.filesDir, "baseline-retained-file.txt")
        file.writeText("retained")
        try {
            withDevelopmentDatabase { room ->
                room.openHelper.writableDatabase.execSQL("CREATE TABLE old_baseline_marker (value TEXT)")
                room.openHelper.writableDatabase.execSQL(
                    "UPDATE room_master_table SET identity_hash = 'old-development-baseline' WHERE id = 42",
                )
            }
            withDevelopmentDatabase { room ->
                val db = room.openHelper.writableDatabase
                db.query("SELECT name FROM sqlite_master WHERE name = 'old_baseline_marker'").use {
                    assertFalse(it.moveToFirst())
                }
                db.execSQL("CREATE TABLE compatible_reopen_marker (value TEXT)")
            }
            withDevelopmentDatabase { room ->
                room.openHelper.writableDatabase
                    .query(
                        "SELECT name FROM sqlite_master WHERE name = 'compatible_reopen_marker'",
                    ).use { assertTrue(it.moveToFirst()) }
            }
            assertEquals("retained", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun earlierDevelopmentVersionIsRebuiltWithoutDeletingExternalFiles() {
        val file = java.io.File(context.filesDir, "earlier-baseline-retained.txt")
        file.writeText("retained")
        try {
            context.openOrCreateDatabase(DATABASE, 0, null).use { old ->
                old.execSQL("CREATE TABLE earlier_development_marker (value TEXT)")
                old.version = 9
            }
            withDevelopmentDatabase { room ->
                val db = room.openHelper.writableDatabase
                assertEquals(1, db.version)
                db.query("SELECT name FROM sqlite_master WHERE name = 'earlier_development_marker'").use {
                    assertFalse(it.moveToFirst())
                }
                db.execSQL("CREATE TABLE compatible_version_marker (value TEXT)")
            }
            withDevelopmentDatabase { room ->
                room.openHelper.writableDatabase
                    .query(
                        "SELECT name FROM sqlite_master WHERE name = 'compatible_version_marker'",
                    ).use { assertTrue(it.moveToFirst()) }
            }
            assertEquals("retained", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun freshDatabaseUsesVersionOneWithForeignKeysAndCurrentTurnIndexes() {
        context.deleteDatabase(DATABASE)
        val room = Room.databaseBuilder(context, HelixDatabase::class.java, DATABASE).build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("PRAGMA user_version").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            db.query("PRAGMA foreign_keys").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }

            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue("turn_runtime_records" in tables)
            assertTrue("turn_review_receipts" in tables)
            assertTrue("tool_call_reviews" in tables)
            assertFalse("tool_approval_preferences" in tables)
            assertFalse("tool_registration_baseline" in tables)
            assertFalse("tool_baseline_meta" in tables)
            assertFalse("composer_drafts" in tables)
            // The CUSTOM permission configuration is active data, not an unsent-message cache.
            assertTrue("session_permission_drafts" in tables)

            val turnIndexes = mutableMapOf<String, Boolean>()
            db.query("PRAGMA index_list('turns')").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
                while (cursor.moveToNext()) {
                    turnIndexes[cursor.getString(nameColumn)] = cursor.getInt(uniqueColumn) == 1
                }
            }
            assertEquals(true, turnIndexes["index_turns_clientRequestId"])
            assertEquals(false, turnIndexes["index_turns_recoveryFromTurnId"])
        } finally {
            room.close()
        }
    }

    private fun withDevelopmentDatabase(block: (HelixDatabase) -> Unit) {
        val room = HelixStorage.openDevelopmentDatabase(context, DATABASE)
        try {
            block(room)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DATABASE = "fresh-baseline.db"
    }
}
