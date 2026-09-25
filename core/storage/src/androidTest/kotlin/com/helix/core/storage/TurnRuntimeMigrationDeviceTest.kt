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

/** v29 -> v30 contract: legacy Turns stay snapshot-less; new runtime rows cascade with their Turn. */
@RunWith(AndroidJUnit4::class)
class TurnRuntimeMigrationDeviceTest {
    private lateinit var context: Context
    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB)
        helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                HelixDatabase::class.java,
            )
    }

    @Test
    fun migrationLeavesLegacyTurnsWithoutGuessedRuntimeAndEnforcesCascade() {
        helper.createDatabase(DB, 29).use { db ->
            db.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s','Session',1)")
            db.execSQL(
                "INSERT INTO turns(id,sessionId,state,stepCount,startedAt) " +
                    "VALUES ('t','s','NEEDS_REVIEW',2,2)",
            )
        }

        helper.runMigrationsAndValidate(DB, 30, true, HelixDatabase.MIGRATION_29_30).use { db ->
            db.query("SELECT COUNT(*) FROM turn_runtime_records").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.execSQL(
                "INSERT INTO turn_runtime_records(" +
                    "turnId,version,providerId,modelId,providerSnapshot,mode,chatToolsEnabled," +
                    "budgetsJson,reasoning,goalBudgetsJson,consumedModelCalls,consumedTokens,admittedToolRounds" +
                    ") VALUES ('t',1,'p','m','{}','ACT',1,'{}','LOW','{}',0,0,0)",
            )
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM turns WHERE id='t'")
            db.query("SELECT COUNT(*) FROM turn_runtime_records").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    private companion object {
        const val DB = "turn-runtime-migration"
    }
}
