package com.helix.core.storage

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** v30 -> v31 contract: successor identity and clean-slate Turn review receipts are durable. */
@RunWith(AndroidJUnit4::class)
class TurnRecoveryRelationMigrationDeviceTest {
    private lateinit var context: Context
    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HelixDatabase::class.java)
    }

    @Test
    fun migrationAddsNullableRecoveryPredecessorAndIndex() {
        helper.createDatabase(DB, 30).use { db ->
            db.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s','Session',1)")
            db.execSQL("INSERT INTO turns(id,sessionId,state,stepCount,startedAt) VALUES ('old','s','INTERRUPTED',1,2)")
        }

        helper.runMigrationsAndValidate(DB, 31, true, HelixDatabase.MIGRATION_30_31).use { db ->
            db.query("SELECT recoveryFromTurnId FROM turns WHERE id='old'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
            db.query("PRAGMA table_info(`turn_runtime_records`)").use { cursor ->
                val columns =
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                assertTrue("legacy review receipt columns must be removed", "reviewActionId" !in columns)
                assertTrue("legacy review model identity must be removed", "reviewModelCallId" !in columns)
            }
            db.execSQL(
                "INSERT INTO turns(id,sessionId,state,stepCount,startedAt,recoveryFromTurnId) " +
                    "VALUES ('next','s','CREATED',0,3,'old')",
            )
            db.query("SELECT recoveryFromTurnId FROM turns WHERE id='next'").use {
                assertTrue(it.moveToFirst())
                assertEquals("old", it.getString(0))
            }
            assertRecoveryIndex(db)
            db.execSQL(
                "INSERT INTO turn_review_receipts(turnId,clientActionId,actionFingerprint) VALUES (?,?,?)",
                arrayOf<Any>("next", "action-1", "a".repeat(64)),
            )
            db.query("SELECT clientActionId FROM turn_review_receipts WHERE turnId='next'").use {
                assertTrue(it.moveToFirst())
                assertEquals("action-1", it.getString(0))
            }
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM turns WHERE id='next'")
            db.query("SELECT COUNT(*) FROM turn_review_receipts WHERE turnId='next'").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    private fun assertRecoveryIndex(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("PRAGMA index_list(`turns`)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_turns_recoveryFromTurnId") {
                    found = true
                }
            }
            assertTrue("recovery predecessor index missing", found)
        }
    }

    private companion object {
        const val DB = "turn-recovery-relation-migration"
    }
}
