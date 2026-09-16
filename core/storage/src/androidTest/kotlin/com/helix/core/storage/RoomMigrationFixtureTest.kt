package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.ProviderProtocol
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalMintOutcome
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.criteria.StoredCriterion
import com.helix.core.storage.mapping.StoredGoal
import com.helix.core.storage.repository.ProviderConfigSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Room migration fixture (HXA-014). The committed schema export in
 * `src/androidTest/assets` is the migration baseline:
 *
 * - the export/code drift loop is closed by [v20ExportMatchesTheCodeBuiltSchema] (the live
 *   version) plus the JVM contract test; the committed v1 export stays the migration
 *   baseline used by [v1ToV2MigrationRenamesBindingHashAndExpiresLegacyApprovals];
 * - [v1EnforcesForeignKeysAtRuntime] proves the runtime schema enables FK enforcement;
 * - the CRUD round-trip and FK-violation tests exercise the v1 fixture end to end.
 *
 * Future schema changes add a `Migration` object plus a new exported version here
 * (doc 9.2: migrations require a schema export and an instrumentation test). The v1 -> v2
 * migration (HXA-034: approvals gain `expiresAt`, `argsHash` becomes `bindingHash`) is
 * covered by [v1ToV2MigrationRenamesBindingHashAndExpiresLegacyApprovals], which now runs
 * through the FULL production chain (v1 -> v2 -> v3 -> v4) because Room only opens a v1 file
 * when every step up to the live version is registered. The v2 -> v3 step (HXA-037: adds
 * `interaction_receipts`) and the v3 -> v4 step (HXA-049: adds `message_attachments`) are
 * exercised by the same chain.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationFixtureTest {
    private lateinit var context: Context
    private lateinit var helper: MigrationTestHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Fresh fixture per run: installed APKs keep app data between connected-test runs,
        // so stale rows would break the round-trip assertions.
        listOf(EXPORTED_DB, CODE_DB, FK_DB, MIGRATION_DB).forEach { context.deleteDatabase(it) }
        helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                HelixDatabase::class.java,
            )
    }

    @Test
    fun v9ToV10PreservesSessionsAndAddsOptionalDirectory() {
        val name = "session-directory-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 9).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('old','Original title',1)")
        }
        helper.runMigrationsAndValidate(name, 10, true, HelixDatabase.MIGRATION_9_10).use { db ->
            db.query("SELECT title,directoryRef FROM sessions WHERE id='old'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Original title", it.getString(0))
                assertTrue(it.isNull(1))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v10ToV11KeepsTurnsAndAddsNullableTaskReceipts() {
        val name = "task-receipt-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 10).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('old','Original title',1)")
            it.execSQL(
                "INSERT INTO goals(id,objective,criteria,budgets,state,correlationId,runCount,modelCalls," +
                    "toolCalls,totalTokens,runTimeMillis,currentWakeMillis,retries) " +
                    "VALUES ('g','fixture','[]','{}','PAUSED','c',1,1,0,10,0,0,0)",
            )
            it.execSQL(
                "INSERT INTO goal_runs(id,goalId,wakeReason,outcome,startedAt,endedAt," +
                    "modelCalls,toolCalls,tokens) " +
                    "VALUES ('r','g','USER_OPEN','BUDGET_EXHAUSTED(maxModelCalls)',1,2,1,0,10)",
            )
            it.execSQL("INSERT INTO turns(id,sessionId,state,stepCount,startedAt) VALUES ('t','old','COMPLETED',0,1)")
        }
        helper.runMigrationsAndValidate(name, 11, true, HelixDatabase.MIGRATION_10_11).use { db ->
            db.query("SELECT state FROM goals WHERE id='g'").use {
                assertTrue(it.moveToFirst())
                assertEquals("BLOCKED", it.getString(0))
            }
            db.query("SELECT state,resultCollectedAt,pauseRequestedAt FROM turns WHERE id='t'").use {
                assertTrue(it.moveToFirst())
                assertEquals("COMPLETED", it.getString(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v11ToV12ReleasesOnlyRetiredEvidenceBlockerWithoutStartingRuns() {
        val name = "model-goal-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 11).use { db ->
            for ((id, reason) in listOf("binding" to "EVIDENCE_BINDING_REQUIRED", "budget" to "remainingBudget")) {
                db.execSQL(
                    "INSERT INTO goals(id,objective,criteria,budgets,state,correlationId,runCount,modelCalls," +
                        "toolCalls,totalTokens,runTimeMillis,currentWakeMillis,retries) " +
                        "VALUES (?, 'fixture','[]','{}','BLOCKED','c',1,1,0,10,0,0,0)",
                    arrayOf(id),
                )
                val outcome = if (id == "binding") "BLOCKED($reason)" else "BUDGET_EXHAUSTED($reason)"
                db.execSQL(
                    "INSERT INTO goal_runs(id,goalId,wakeReason,outcome,startedAt,endedAt," +
                        "modelCalls,toolCalls,tokens) " +
                        "VALUES (?,?,'USER_OPEN',?,1,2,1,0,10)",
                    arrayOf("r-$id", id, outcome),
                )
            }
        }
        helper.runMigrationsAndValidate(name, 12, true, HelixDatabase.MIGRATION_11_12).use { db ->
            db.query("SELECT id,state,totalTokens FROM goals ORDER BY id").use {
                assertTrue(it.moveToFirst())
                assertEquals("binding", it.getString(0))
                assertEquals("PAUSED", it.getString(1))
                assertEquals(10L, it.getLong(2))
                assertTrue(it.moveToNext())
                assertEquals("BLOCKED", it.getString(1))
            }
            db.query("SELECT COUNT(*) FROM goal_runs WHERE endedAt IS NOT NULL").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v1ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        assertTrue("schema export missing from assets: ${versions?.toList()}", versions?.contains("1.json") == true)
    }

    @Test
    fun v2ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        val list = versions?.toList()
        val hasV1 = list?.contains("1.json") ?: false
        val hasV2 = list?.contains("2.json") ?: false
        assertTrue("schema export v1+v2 missing from assets: $list", hasV1 && hasV2)
    }

    @Test
    fun v3ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        assertTrue(
            "schema export v3 missing from assets: ${versions?.toList()}",
            versions?.contains("3.json") == true,
        )
    }

    @Test
    fun v4ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        assertTrue(
            "schema export v4 missing from assets: ${versions?.toList()}",
            versions?.contains("4.json") == true,
        )
    }

    @Test
    fun v5ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        assertTrue(
            "schema export v5 missing from assets: ${versions?.toList()}",
            versions?.contains("5.json") == true,
        )
    }

    @Test
    fun v6ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        assertTrue(
            "schema export v6 missing from assets: ${versions?.toList()}",
            versions?.contains("6.json") == true,
        )
    }

    @Test
    fun v7ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        assertTrue(
            "schema export v7 missing from assets: ${versions?.toList()}",
            versions?.contains("7.json") == true,
        )
    }

    @Test
    fun v7ToV8PreservesSessionsAndDoesNotInventGoalBindings() {
        val name = "goal-binding-upgrade.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 7).use { old ->
            old.execSQL("INSERT INTO sessions (id, title, createdAt) VALUES ('legacy', 'Existing session', 1000)")
        }
        helper.runMigrationsAndValidate(name, 8, true, HelixDatabase.MIGRATION_7_8).use { upgraded ->
            upgraded.query("SELECT title FROM sessions WHERE id = 'legacy'").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Existing session", rows.getString(0))
            }
            upgraded.query("SELECT COUNT(*) FROM goal_turn_bindings").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0, rows.getInt(0))
            }
        }
    }

    @Test
    fun v8ToV9PreservesSessionsAndStartsWithNoReservations() {
        val name = "v8-to-v9.db"
        helper.createDatabase(name, 8).use {
            it.execSQL("INSERT INTO sessions (id, title, createdAt) VALUES ('legacy', 'Existing session', 1000)")
        }
        helper.runMigrationsAndValidate(name, 9, true, HelixDatabase.MIGRATION_8_9).use { upgraded ->
            upgraded.query("SELECT title FROM sessions WHERE id = 'legacy'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Existing session", it.getString(0))
            }
            upgraded.query("SELECT COUNT(*) FROM goal_usage_reservations").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun v12ToV13AddsThePersistentSubmitDedupReceiptToTurns() {
        val name = "turn-submit-dedup-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 12).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s1','S',1)")
            it.execSQL("INSERT INTO turns(id,sessionId,state,stepCount,startedAt) VALUES ('t','s1','COMPLETED',0,1)")
        }
        helper.runMigrationsAndValidate(name, 13, true, HelixDatabase.MIGRATION_12_13).use { db ->
            // The pre-v13 row keeps a NULL receipt (never matched by a non-null re-drive query).
            db.query("SELECT clientRequestId, inputFingerprint FROM turns WHERE id='t'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertTrue(it.isNull(1))
            }
            // A v13 turn records its client-request receipt, and a second NULL receipt is allowed
            // (NULLs stay distinct under the unique index)...
            db.execSQL(
                "INSERT INTO turns(id,sessionId,state,stepCount,startedAt,clientRequestId,inputFingerprint) " +
                    "VALUES ('t2','s1','CREATED',0,1,'req-1','fp-1')",
            )
            db.execSQL(
                "INSERT INTO turns(id,sessionId,state,stepCount,startedAt,clientRequestId,inputFingerprint) " +
                    "VALUES ('t3','s1','CREATED',0,1,NULL,NULL)",
            )
            // ...but a duplicate clientRequestId is refused by the unique index — the DB-level
            // backstop so one client-request id can never back a second turn.
            assertThrows(
                android.database.SQLException::class.java,
            ) {
                db.execSQL(
                    "INSERT INTO turns(id,sessionId,state,stepCount,startedAt,clientRequestId,inputFingerprint) " +
                        "VALUES ('t4','s1','CREATED',0,1,'req-1','fp-other')",
                )
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v13ToV14AddsThePromptRecordToModelCalls() {
        val name = "prompt-record-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 13).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s1','S',1)")
            it.execSQL("INSERT INTO turns(id,sessionId,state,stepCount,startedAt) VALUES ('t','s1','COMPLETED',0,1)")
            it.execSQL(
                "INSERT INTO model_calls(id,turnId,providerSnapshot,state) VALUES ('c','t','prov','RUNNING')",
            )
        }
        helper.runMigrationsAndValidate(name, 14, true, HelixDatabase.MIGRATION_13_14).use { db ->
            // The pre-v14 call keeps a NULL prompt record (it was never recorded).
            db.query("SELECT promptFingerprint, promptSections FROM model_calls WHERE id='c'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertTrue(it.isNull(1))
            }
            // A v14 call records the redacted prompt record on its row (fingerprint + section list).
            db.execSQL(
                "UPDATE model_calls SET promptFingerprint = 'fp', promptSections = '[\"sections\"]' WHERE id = 'c'",
            )
            db.query("SELECT promptFingerprint, promptSections FROM model_calls WHERE id='c'").use {
                assertTrue(it.moveToFirst())
                assertEquals("fp", it.getString(0))
                assertEquals("[\"sections\"]", it.getString(1))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v14ToV15AddsTheTurnIdToArtifacts() {
        val name = "artifact-turn-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 14).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s1','S',1)")
            it.execSQL(
                "INSERT INTO artifacts(id,sessionId,relativePath,mediaType,size,sha256) " +
                    "VALUES ('a1','s1','output/legacy.txt','text/plain',3,'${"a".repeat(64)}')",
            )
        }
        helper.runMigrationsAndValidate(name, 15, true, HelixDatabase.MIGRATION_14_15).use { db ->
            // The pre-v15 row keeps a NULL turn (no turn attribution is invented).
            db.query("SELECT turnId FROM artifacts WHERE id='a1'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
            // A v15 registration records the writing turn on its row.
            db.execSQL(
                "INSERT INTO artifacts(id,sessionId,relativePath,mediaType,size,sha256,turnId) " +
                    "VALUES ('a2','s1','output/new.txt','text/plain',3,'${"b".repeat(64)}','t-1')",
            )
            db.query("SELECT turnId FROM artifacts WHERE id='a2'").use {
                assertTrue(it.moveToFirst())
                assertEquals("t-1", it.getString(0))
            }
            // The unique (sessionId, relativePath) index still refuses a second row per path —
            // re-writes go through the upsert, not a second insert.
            assertThrows(
                android.database.SQLException::class.java,
            ) {
                db.execSQL(
                    "INSERT INTO artifacts(id,sessionId,relativePath,mediaType,size,sha256,turnId) " +
                        "VALUES ('a3','s1','output/new.txt','text/plain',3,'${"c".repeat(64)}','t-2')",
                )
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v15ToV16NormalizesLegacyArtifactPathsToFullScopeRefs() {
        val name = "artifact-scope-ref-migration"
        val legacyPaths =
            listOf("output/legacy.txt", "scope:notes.txt", "scope:other:output/x.txt", "scope:app:output/legacy.txt")
        context.deleteDatabase(name)
        helper.createDatabase(name, 15).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s1','S',1)")
            // All v15 rows are bare paths, including legal names that resemble full refs.
            legacyPaths.forEachIndexed { index, path ->
                it.execSQL(
                    "INSERT INTO artifacts(id,sessionId,relativePath,mediaType,size,sha256,turnId) " +
                        "VALUES (?, 's1', ?, 'text/plain', 3, ?, ?)",
                    arrayOf("a$index", path, "a".repeat(64), if (index == 0) null else "t-$index"),
                )
            }
        }
        helper.runMigrationsAndValidate(name, 16, true, HelixDatabase.MIGRATION_15_16).use { db ->
            db.query("SELECT id, relativePath, sha256, turnId, size, mediaType FROM artifacts ORDER BY id").use {
                assertEquals(legacyPaths.size, it.count)
                legacyPaths.forEachIndexed { index, path ->
                    assertTrue(it.moveToNext())
                    assertEquals("a$index", it.getString(0))
                    assertEquals("scope:app:$path", it.getString(1))
                    assertEquals("a".repeat(64), it.getString(2))
                    assertEquals(if (index == 0) null else "t-$index", it.getString(3))
                    assertEquals(3L, it.getLong(4))
                    assertEquals("text/plain", it.getString(5))
                }
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v16ToV17AddsTheToolApprovalPreferenceTableEmptyOnUpgrade() {
        val name = "tool-approval-preference-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 16).use {
            // Legacy rows prove existing data survives the additive v16 -> v17 step.
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s1','S',1)")
            it.execSQL("INSERT INTO turns(id,sessionId,state,stepCount,startedAt) VALUES ('t','s1','COMPLETED',0,1)")
        }
        helper.runMigrationsAndValidate(name, 17, true, HelixDatabase.MIGRATION_16_17).use { db ->
            // The legacy rows survive the additive migration.
            db.query("SELECT title FROM sessions WHERE id='s1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("S", it.getString(0))
            }
            // The new table exists and is EMPTY on upgrade: the migration bulk-creates NO ALLOW
            // (or any) rows, so an existing unconfigured user keeps their original card-free
            // behavior (ADR-0052 point 1).
            db.query("SELECT COUNT(*) FROM tool_approval_preferences").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            // The unique (sourceRef, toolName, scopeKind, scopeRef) key is enforced on the
            // migrated table — "reset to default" is a delete, not a fourth state (point 5).
            db.execSQL(
                "INSERT INTO tool_approval_preferences " +
                    "(id, sourceRef, toolName, preference, scopeKind, scopeRef, contractHash, " +
                    "revision, createdAtEpoch, updatedAtEpoch) " +
                    "VALUES ('p1','src','tool','ASK','GLOBAL','','',0,1,1)",
            )
            assertThrows(
                android.database.SQLException::class.java,
            ) {
                db.execSQL(
                    "INSERT INTO tool_approval_preferences " +
                        "(id, sourceRef, toolName, preference, scopeKind, scopeRef, contractHash, " +
                        "revision, createdAtEpoch, updatedAtEpoch) " +
                        "VALUES ('p2','src','tool','ALLOW','GLOBAL','','x',0,2,2)",
                )
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v17ToV18AddsTheTrustedToolBaselineTablesEmptyOnUpgrade() {
        val name = "tool-baseline-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 17).use {
            // Legacy rows: a stored ALLOW bound to a contract and a narrower SESSION ASK —
            // existing user data the additive v17 -> v18 step must not touch.
            it.execSQL(
                "INSERT INTO tool_approval_preferences " +
                    "(id, sourceRef, toolName, preference, scopeKind, scopeRef, contractHash, " +
                    "revision, createdAtEpoch, updatedAtEpoch) " +
                    "VALUES ('p-allow','builtin','files.write','ALLOW','GLOBAL','','c1',0,1,1)",
            )
            it.execSQL(
                "INSERT INTO tool_approval_preferences " +
                    "(id, sourceRef, toolName, preference, scopeKind, scopeRef, contractHash, " +
                    "revision, createdAtEpoch, updatedAtEpoch) " +
                    "VALUES ('p-ask','builtin','files.write','ASK','SESSION','s1','',0,2,2)",
            )
        }
        helper.runMigrationsAndValidate(name, 18, true, HelixDatabase.MIGRATION_17_18).use { db ->
            // The trusted baseline tables (HXA-200 Gap 2, ADR-0052 point 1) exist but are EMPTY
            // on upgrade: no seeded founding anchor, no per-tool first-seen markers — the migration
            // bulk-creates no "new" marking and no ALLOW (既有工具 UNSET).
            assertTableEmpty(db, "tool_registration_baseline")
            assertTableEmpty(db, "tool_baseline_meta")
            // The pre-existing preference rows survive the additive migration, byte-for-byte.
            assertPreferenceRow(db, "p-allow", "ALLOW", "GLOBAL", "", "c1")
            assertPreferenceRow(db, "p-ask", "ASK", "SESSION", "s1", "")
            // The unique (sourceRef, toolName) key is enforced on the migrated marker table — the
            // first-write-wins stamp that keeps firstSeenVersionCode immutable across restarts.
            db.execSQL(
                "INSERT INTO tool_registration_baseline " +
                    "(sourceRef, toolName, firstSeenVersionCode, updatedAtEpoch) " +
                    "VALUES ('builtin','a.tool',1,1)",
            )
            assertThrows(
                android.database.SQLException::class.java,
            ) {
                db.execSQL(
                    "INSERT INTO tool_registration_baseline " +
                        "(sourceRef, toolName, firstSeenVersionCode, updatedAtEpoch) " +
                        "VALUES ('builtin','a.tool',2,2)",
                )
            }
        }
        context.deleteDatabase(name)
    }

    /** The v18 baseline tables carry no rows after the upgrade (no seeded data). */
    private fun assertTableEmpty(
        db: SupportSQLiteDatabase,
        table: String,
    ) {
        db.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    /** One pre-existing preference row survived the additive v17 -> v18 migration intact. */
    private fun assertPreferenceRow(
        db: SupportSQLiteDatabase,
        id: String,
        preference: String,
        scopeKind: String,
        scopeRef: String,
        contractHash: String,
    ) {
        db
            .query(
                "SELECT preference, scopeKind, scopeRef, contractHash " +
                    "FROM tool_approval_preferences WHERE id='$id'",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(preference, it.getString(0))
                assertEquals(scopeKind, it.getString(1))
                assertEquals(scopeRef, it.getString(2))
                assertEquals(contractHash, it.getString(3))
            }
    }

    @Test
    fun productionOpenMigratesAV16DatabaseToV19() {
        val name = "prod-upgrade-v16.db"
        val contentDir = File(context.cacheDir, "content-$name")
        context.deleteDatabase(name)
        contentDir.deleteRecursively()
        // A real v16 database file (committed export) carrying a legacy row — the state an
        // existing user's install has before the v18 app launches.
        helper.createDatabase(name, 16).use {
            it.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s1','S',1)")
        }
        // Open it through the PRODUCTION factory: HelixStorage.open applies ALL_MIGRATIONS, the
        // exact chain a real install upgrade runs. A migration added to the schema but forgotten
        // in ALL_MIGRATIONS crashes here ("A migration from 16 to 18 is required") — the gap that
        // left the v16 -> v17 step unregistered (HXA-200).
        val storage = HelixStorage.open(context, name, contentDir)
        try {
            val sqlite = storage.database.openHelper.writableDatabase
            // Upgraded to the live schema: the legacy session row survived.
            sqlite.query("SELECT title FROM sessions WHERE id='s1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("S", it.getString(0))
            }
            // The legacy HXA-200 tables are DROPPED at v21 (HXA-209 B4, ADR-PERMISSIONS-001
            // section 4): the production chain leaves no compatibility mode that reads them.
            assertFalse("tool_approval_preferences" in tables(sqlite))
            assertFalse("tool_registration_baseline" in tables(sqlite))
            assertFalse("tool_baseline_meta" in tables(sqlite))
        } finally {
            storage.database.close()
        }
    }

    @Test
    fun v18ToV19PreservesOwnershipWithoutActivatingOrStagingWork() {
        val name = "goal-control-v19.db"
        helper.createDatabase(name, 18).use { db ->
            db.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s','Goal fixture',1)")
            db.execSQL(
                "INSERT INTO goals(id,objective,criteria,budgets,state,correlationId,runCount,modelCalls," +
                    "toolCalls,totalTokens,runTimeMillis,currentWakeMillis,retries) " +
                    "VALUES ('g','fixture','[]','{}','PAUSED','c',1,1,0,10,0,0,0)",
            )
            db.execSQL(
                "INSERT INTO goal_runs(id,goalId,wakeReason,outcome,startedAt,endedAt,modelCalls,toolCalls,tokens) " +
                    "VALUES ('r','g','USER_OPEN','RUN_FINISHED',1,2,1,0,10)",
            )
            db.execSQL("INSERT INTO turns(id,sessionId,state,stepCount,startedAt) VALUES ('t','s','COMPLETED',0,1)")
            db.execSQL("INSERT INTO goal_turn_bindings(turnId,runId) VALUES ('t','r')")
        }
        helper.runMigrationsAndValidate(name, 19, true, HelixDatabase.MIGRATION_18_19).use { db ->
            db.query("SELECT goalId,sessionId,revision,pendingTurnId,pendingJson FROM goal_controls").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("g", row.getString(0))
                assertEquals("s", row.getString(1))
                assertEquals(0L, row.getLong(2))
                assertTrue(row.isNull(3) && row.isNull(4))
                assertFalse(row.moveToNext())
            }
            db.query("SELECT state,totalTokens FROM goals WHERE id='g'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("PAUSED", row.getString(0))
                assertEquals(10L, row.getLong(1))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v19ToV20ConvertsDenyPreferencesAndSeedsReadonlyDefault() {
        val name = "session-permission-v20.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 19).use { it.seedLegacyApprovalPreferences() }
        helper.runMigrationsAndValidate(name, 20, true, HelixDatabase.MIGRATION_19_20).use { db ->
            db.assertConvertedToolAvailability()
            db.assertSeededAppDefault()
            // The per-session config table is LAZY: no rows are seeded for existing sessions.
            db.query("SELECT COUNT(*) FROM session_permission_configs").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            // The old table is still present at v20 (its rows already consumed into
            // tool_availability); [MIGRATION_20_21] (HXA-209 B4) drops it.
            db.query("SELECT COUNT(*) FROM tool_approval_preferences").use {
                assertTrue(it.moveToFirst())
                assertEquals(4, it.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v20ToV21DropsTheLegacyToolApprovalTables() {
        val name = "legacy-tool-approval-removal.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 20).use { it.seedLegacyToolApprovalTables() }
        helper.runMigrationsAndValidate(name, 21, true, HelixDatabase.MIGRATION_20_21).use { db ->
            // All three legacy HXA-200 tables are GONE — no hidden compatibility mode reads them
            // (ADR-PERMISSIONS-001 section 4).
            assertTableDropped(db, "tool_approval_preferences")
            assertTableDropped(db, "tool_registration_baseline")
            assertTableDropped(db, "tool_baseline_meta")
            // The surviving intent is byte-for-byte intact: the converted DISABLED rows (the old
            // DENY rows' effect) and the READ_ONLY app default.
            db.assertConvertedToolAvailability()
            db.assertSeededAppDefault()
            // The v20 session-permission tables survive the drop untouched.
            db.query("SELECT COUNT(*) FROM session_permission_configs").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v21ToV22AddsTheCustomDraftTableAndKeepsExistingRows() {
        val name = "custom-draft-addition.db"
        context.deleteDatabase(name)
        // A v21 file in the state a real upgrade has: one session with a stored CUSTOM config,
        // one disabled tool and the app default. The sessions row keeps the config FK consistent.
        helper.createDatabase(name, 21).use { db ->
            db.execSQL("INSERT INTO sessions(id,title,createdAt) VALUES ('s-1','session one',10)")
            db.execSQL(
                "INSERT INTO session_permission_configs (sessionId, mode, rulesJson, configVersion, " +
                    "revision, createdAtEpoch, updatedAtEpoch) " +
                    "VALUES ('s-1', 'CUSTOM', '{}', 1, 3, 100, 200)",
            )
            db.execSQL(
                "INSERT INTO tool_availability (sourceRef, toolName, scopeKind, scopeRef, state, " +
                    "revision, createdAtEpoch, updatedAtEpoch) " +
                    "VALUES ('local', 'bash', 'SESSION', 's-1', 'DISABLED', 2, 300, 400)",
            )
            db.execSQL(
                "INSERT INTO session_permission_defaults (id, mode, configVersion, revision, " +
                    "updatedAtEpoch) VALUES ('app', 'WORKSPACE', 1, 5, 900)",
            )
        }
        helper.runMigrationsAndValidate(name, 22, true, HelixDatabase.MIGRATION_21_22).use { db ->
            // The additive table is PRESENT and EMPTY — an upgrade seeds no draft rows.
            assertTrue(
                "v22 upgrade must add session_permission_drafts",
                "session_permission_drafts" in tables(db),
            )
            db.query("SELECT COUNT(*) FROM session_permission_drafts").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            // The pre-existing v21 rows survive byte-for-byte — the migration only ADDS a
            // table, it rewrites nothing.
            db
                .query(
                    "SELECT mode, configVersion, revision FROM session_permission_configs " +
                        "WHERE sessionId = 's-1'",
                ).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("CUSTOM", it.getString(0))
                    assertEquals(1, it.getInt(1))
                    assertEquals(3, it.getInt(2))
                    assertFalse(it.moveToNext())
                }
            db.query("SELECT state FROM tool_availability WHERE toolName = 'bash'").use {
                assertTrue(it.moveToFirst())
                assertEquals("DISABLED", it.getString(0))
            }
            db.query("SELECT mode FROM session_permission_defaults WHERE id = 'app'").use {
                assertTrue(it.moveToFirst())
                assertEquals("WORKSPACE", it.getString(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test
    fun v22ExportMatchesTheCodeBuiltSchema() {
        val exportedDb = helper.createDatabase("v22-export.db", 22)
        val exported = schemaFacts(exportedDb)
        exportedDb.close()

        val codeDb = Room.databaseBuilder(context, HelixDatabase::class.java, CODE_DB).build()
        try {
            val code = schemaFacts(codeDb.openHelper.writableDatabase)
            assertEquals(
                "code-built v22 schema must match the exported v22 schema",
                expectedTables().sorted(),
                code.tables.sorted(),
            )
            assertEquals(
                "column sets (name/type/nullability/PK) drifted between export and code",
                exported.columns,
                code.columns,
            )
            assertEquals(
                "foreign keys (parent table/column, on-delete) drifted between export and code",
                exported.foreignKeys,
                code.foreignKeys,
            )
            assertEquals("indexes drifted between export and code", exported.indexes, code.indexes)
        } finally {
            codeDb.close()
        }
    }

    @Test
    fun v1ToV2MigrationRenamesBindingHashAndExpiresLegacyApprovals() {
        val db = helper.createDatabase(MIGRATION_DB, 1)
        // The v1 fixture does not enforce FKs on this raw connection, so the approvals rows
        // stand in for the full session/turn/tool-call chain.
        db.execSQL(
            "INSERT INTO approvals (id, toolCallId, argsHash, decision, decidedAt, consumedAt) " +
                "VALUES ('approval-mig-1', 'toolcall-mig-1', '${"p".repeat(64)}', NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO approvals (id, toolCallId, argsHash, decision, decidedAt, consumedAt) " +
                "VALUES ('approval-mig-2', 'toolcall-mig-2', '${"q".repeat(64)}', 'APPROVED', 10, 20)",
        )
        db.close()
        // Room opens the v1 file and applies the FULL committed chain (1 -> ... -> 22) —
        // the exact production path (HelixStorage.ALL_MIGRATIONS registers the same set;
        // including the room_master_table identity update). The assertions below verify the
        // 1 -> 2 step specifically; the chain also proves every later migration step applies.
        val roomDb =
            Room
                .databaseBuilder(context, HelixDatabase::class.java, MIGRATION_DB)
                .addMigrations(
                    HelixDatabase.MIGRATION_1_2,
                    HelixDatabase.MIGRATION_2_3,
                    HelixDatabase.MIGRATION_3_4,
                    HelixDatabase.MIGRATION_4_5,
                    HelixDatabase.MIGRATION_5_6,
                    HelixDatabase.MIGRATION_6_7,
                    HelixDatabase.MIGRATION_7_8,
                    HelixDatabase.MIGRATION_8_9,
                    HelixDatabase.MIGRATION_9_10,
                    HelixDatabase.MIGRATION_10_11,
                    HelixDatabase.MIGRATION_11_12,
                    HelixDatabase.MIGRATION_12_13,
                    HelixDatabase.MIGRATION_13_14,
                    HelixDatabase.MIGRATION_14_15,
                    HelixDatabase.MIGRATION_15_16,
                    HelixDatabase.MIGRATION_16_17,
                    HelixDatabase.MIGRATION_17_18,
                    HelixDatabase.MIGRATION_18_19,
                    HelixDatabase.MIGRATION_19_20,
                    HelixDatabase.MIGRATION_20_21,
                    HelixDatabase.MIGRATION_21_22,
                ).build()
        try {
            val sqlite = roomDb.openHelper.writableDatabase
            val columns = pragmaRows(sqlite, "PRAGMA table_info(approvals)").map { row -> row[1] }.toSet()
            assertTrue(
                "v2 approvals must carry bindingHash + expiresAt and drop argsHash: $columns",
                "bindingHash" in columns && "expiresAt" in columns && "argsHash" !in columns,
            )

            // Rows survive the migration; the hash content is preserved under the new name.
            assertEquals(
                listOf("p".repeat(64), null, null, "0"),
                approvalColumns(sqlite, "approval-mig-1"),
            )
            assertEquals(
                listOf("q".repeat(64), "APPROVED", "20", "0"),
                approvalColumns(sqlite, "approval-mig-2"),
            )
            // expiresAt = 0: every migrated approval is already expired (fail closed) — the
            // old APPROVED row can never consume a proof post-migration (SQL guard).
            assertEquals(0, roomDb.approvalDao().consumeByBinding("approval-mig-2", "q".repeat(64), 30L, 30L))
            // Every later step (v3 receipts, v4 attachments, v5 egress, v6/v7 a2a) must have
            // landed in the live schema — the full chain applies, not just the 1 -> 2 rename.
            assertLaterMigrationStepsLanded(sqlite)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun v1EnforcesForeignKeysAtRuntime() {
        val db = Room.databaseBuilder(context, HelixDatabase::class.java, FK_DB).build()
        try {
            val cursor = db.openHelper.writableDatabase.query("PRAGMA foreign_keys;")
            try {
                assertTrue("PRAGMA foreign_keys must return a row", cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun v1RoundTripsSessionsMessagesTurnsAndContent() {
        withStorage("crud-conversation.db") { storage ->
            // sessions.providerId is an FK to provider_configs: the session must reference a
            // real provider row.
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = "provider-crud",
                    displayName = "Crud Provider",
                    protocol = ProviderProtocol.OPENAI_RESPONSES,
                    endpoint = "http://localhost:1",
                    model = "model-1",
                    headersJson = "{}",
                    secretAlias = "alias-only",
                    capabilitySnapshot = "{}",
                ),
            )
            val session = storage.sessions.create("session-crud", "crud session", "provider-crud", "model-1", 10L)
            assertEquals("provider-crud", storage.sessions.resolve(session.id).providerId)
            storage.sessions.archive(session.id, 20L)
            assertEquals(20L, storage.sessions.resolve(session.id).archivedAt)

            val turn = storage.turns.start("turn-crud-1", session.id, 30L)
            val message =
                storage.messages.append(
                    id = "msg-crud-1",
                    sessionId = session.id,
                    turnId = turn.id,
                    role = "assistant",
                    kind = "text",
                    content = "large message body " + "x".repeat(2048),
                )
            assertEquals(0L, message.sequence)
            val second =
                storage.messages.append(
                    id = "msg-crud-2",
                    sessionId = session.id,
                    turnId = turn.id,
                    role = "user",
                    kind = "text",
                    content = "",
                )
            assertEquals(1L, second.sequence)
            assertEquals(null, storage.messages.readContent(second))

            val content = storage.messages.readContent(message)
            assertNotNull(content)
            assertTrue(content!!.startsWith("large message body"))
        }
    }

    @Test
    fun v1RoundTripsModelCallsToolCallsResultsApprovalsExecutions() {
        withStorage("crud-tools.db") { storage ->
            val session = storage.sessions.create("session-tools", "tools session", null, null, 10L)
            val turn = storage.turns.start("turn-tools-1", session.id, 30L)

            val modelCall =
                storage.modelCalls.append("modelcall-crud-1", turn.id, """{"provider":"local"}""", "RUNNING")
            storage.modelCalls.update(modelCall, "SUCCEEDED", """{"tokens":5}""", "req-1")
            assertEquals("req-1", storage.modelCalls.resolve(modelCall.id).requestId)

            val toolCall =
                storage.toolCalls.append(
                    "toolcall-crud-1",
                    turn.id,
                    "call-9",
                    "bash",
                    "1",
                    """{"cmd":"ls"}""",
                    "PENDING",
                )
            assertEquals(64, toolCall.argsHash.length)
            assertEquals("call-9", storage.toolCalls.byTurnAndCallId(turn.id, "call-9")?.callId)

            val result =
                storage.toolResults.append(
                    id = "toolresult-crud-1",
                    toolCallId = toolCall.id,
                    status = "SUCCESS",
                    summary = "listed files",
                    content = "file listing body",
                )
            storage.toolResults.markVerified(result)
            assertTrue(storage.toolResults.byToolCall(toolCall.id)?.verified == true)
            assertEquals("file listing body", storage.toolResults.readContent(result))

            val approval =
                storage.approvals.create(
                    "approval-crud-1",
                    toolCall.id,
                    approvalBinding(toolCall.id, toolCall.argsHash),
                    40L,
                    100_000L,
                )
            storage.approvals.decide(approval.id, ApprovalDecision.APPROVED, 40L)
            val proof = (storage.approvals.mint(approval.id, 50L) as ApprovalMintOutcome.Minted).proof
            storage.approvals.consume(proof, 50L, 50L)
            val consumed = storage.approvals.resolve(approval.id)
            assertEquals("APPROVED", consumed.decision)
            assertEquals(50L, consumed.consumedAt)

            val execution =
                storage.executions.register("execution-crud-1", toolCall.id, "quickjs", """{"maxMs":1000}""")
            storage.executions.updateOutcome(execution, exitCode = 0, signal = null)
            assertEquals(0, storage.executions.resolve(execution.id).exitCode)
        }
    }

    @Test
    fun v1RoundTripsArtifactsAndAuditEvents() {
        withStorage("crud-plans.db") { storage ->
            val session = storage.sessions.create("session-plans", "plans session", null, null, 10L)

            val artifactFile = File(context.cacheDir, "artifact-crud.txt")
            artifactFile.writeText("artifact body")
            val hash = FileContentStore.sha256Hex(artifactFile.readBytes())
            val artifact =
                storage.artifacts.register(
                    id = "artifact-crud-1",
                    sessionId = session.id,
                    relativePath = "artifacts/artifact-crud.txt",
                    mediaType = "text/plain",
                    size = artifactFile.length(),
                    sha256 = hash,
                    file = artifactFile,
                )
            assertEquals(hash, storage.artifacts.resolve(artifact.id).sha256)

            storage.auditEvents.append("audit-crud-1", session.id, "turn.completed", "agent", """{"turn":"t1"}""", 60L)
            assertEquals(1, storage.auditEvents.listByCorrelation(session.id).size)
        }
    }

    @Test
    fun v1RoundTripsPlansGoalsAndRuns() {
        withStorage("crud-goalruns.db") { storage ->
            val plan =
                PlanArtifact(
                    id = PlanId("plan-crud-1"),
                    objective = "crud plan",
                    assumptions = emptyList(),
                    steps = listOf(PlanStep("step one", "do the thing")),
                    acceptanceCriteria = listOf("it works"),
                    risks = emptyList(),
                    version = 1,
                )
            storage.plans.save(plan, "DRAFT", null)
            assertEquals(plan, storage.plans.resolve(plan.id.value))
            storage.plans.updateState(plan.id.value, "APPROVED", null)
            assertEquals("APPROVED", storage.plans.resolveEntity(plan.id.value).state)

            val goal =
                StoredGoal(
                    id = "goal-crud-1",
                    objective = "crud goal",
                    criteria = listOf(StoredCriterion("c1", "done", null)),
                    budgets = GoalBudgets(4, 8, 1000L, 60_000L, 10_000L, 1),
                    state = "DRAFT",
                    planId = plan.id.value,
                    planHash = plan.sha256().hex,
                    nextCheckpoint = 100L,
                    correlationId = "corr-crud-1",
                    runCount = 0,
                    modelCalls = 0,
                    toolCalls = 0,
                    totalTokens = 0L,
                    runTimeMillis = 0L,
                    currentWakeMillis = 0L,
                    retries = 0,
                    lastWakeReason = null,
                    error = null,
                    finishReason = null,
                )
            storage.goals.save(goal)
            assertEquals(goal, storage.goals.resolve(goal.id))
            assertEquals(1, storage.goals.listByState("DRAFT").size)

            val run = storage.goalRuns.open("goalrun-crud-1", goal.id, "USER_OPEN", 70L)
            storage.goalRuns.finish(run, "SUCCESS", 80L, 5L, 1, 2, 30L)
            val finished = storage.goalRuns.resolve(run.id)
            assertEquals("SUCCESS", finished.outcome)
            assertEquals(80L, finished.endedAt)
        }
    }

    @Test
    fun v1StoresProviderConfigWithAliasAndContentStore() {
        withStorage("crud-config.db") { storage ->
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = "provider-crud-1",
                    displayName = "Local Provider",
                    protocol = ProviderProtocol.OPENAI_RESPONSES,
                    endpoint = "http://localhost:1",
                    model = "m1",
                    headersJson = "{}",
                    secretAlias = "alias-only-no-secret",
                    capabilitySnapshot = "{}",
                ),
            )
            assertEquals("alias-only-no-secret", storage.providerConfigs.resolve("provider-crud-1").secretAlias)

            val ref = storage.contentStore.write("content-store smoke")
            assertEquals("content-store smoke", storage.contentStore.read(ref))
            assertEquals(ref, ContentRef.parse(ref.toStorageString()))
        }
    }

    @Test
    fun v1RejectsRowsThatViolateForeignKeys() {
        withStorage("fk-violation.db") { storage ->
            var thrown: Throwable? = null
            try {
                storage.messages.append(
                    id = "msg-fk-violation",
                    sessionId = "session-does-not-exist",
                    turnId = null,
                    role = "user",
                    kind = "text",
                    content = "orphan message",
                )
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(
                "FK violation must throw SQLiteConstraintException, was: $thrown",
                thrown is android.database.sqlite.SQLiteConstraintException,
            )
            assertTrue(thrown?.message?.contains("FOREIGN KEY constraint failed") == true)
        }
    }

    @Test
    fun v1CascadeFkRemovesChildRowsWhenSessionRowGoesAway() {
        // Schema-level probe: the repositories deliberately expose no session deletion
        // (sessions are archived, never deleted — doc 9.1), so the declared CASCADE FK
        // action is exercised directly on Room's connection (which enforces foreign keys).
        withStorage("cascade.db") { storage ->
            val session = storage.sessions.create("session-cascade", "cascade session", null, null, 1L)
            storage.messages.append("msg-cascade-1", session.id, null, "user", "text", "hello")
            storage.turns.start("turn-cascade-1", session.id, 2L)
            storage.database.openHelper.writableDatabase
                .execSQL("DELETE FROM sessions WHERE id = 'session-cascade'")
            assertTrue(
                "messages must cascade with the session row",
                storage.messages.listBySession(session.id).isEmpty(),
            )
            assertTrue("turns must cascade with the session row", storage.turns.listBySession(session.id).isEmpty())
        }
    }

    @Test
    fun v1ArtifactRegistrationRejectsUnverifiedFiles() {
        // doc 9.2: the file with its hash must exist first, verified by the repository before
        // the row lands. The guard is unconditional: missing file, size mismatch and hash
        // mismatch all fail closed and persist nothing.
        withStorage("crud-artifacts-guard.db") { storage ->
            val session = storage.sessions.create("session-artguard", "artguard session", null, null, 10L)
            val file = File(context.cacheDir, "artifact-guard.txt")
            file.writeText("artifact body")
            val hash = FileContentStore.sha256Hex(file.readBytes())
            assertThrows(IllegalArgumentException::class.java) {
                storage.artifacts.register(
                    "artifact-g-1",
                    session.id,
                    "artifacts/missing.txt",
                    "text/plain",
                    1,
                    "d".repeat(64),
                    File(context.cacheDir, "no-such-artifact.txt"),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                storage.artifacts.register(
                    "artifact-g-2",
                    session.id,
                    "artifacts/artifact-guard.txt",
                    "text/plain",
                    file.length() + 1,
                    hash,
                    file,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                storage.artifacts.register(
                    "artifact-g-3",
                    session.id,
                    "artifacts/artifact-guard.txt",
                    "text/plain",
                    file.length(),
                    "e".repeat(64),
                    file,
                )
            }
            // None of the rejected registrations may have persisted a row.
            for (id in listOf("artifact-g-1", "artifact-g-2", "artifact-g-3")) {
                assertThrows(IllegalArgumentException::class.java) { storage.artifacts.resolve(id) }
            }
        }
    }

    @Test
    fun v1TransactionRollsBackEarlierWritesOnFailure() {
        // A constraint violation mid-transaction must roll back the earlier writes of the
        // same transaction (the repository layers rely on Room transactions for atomicity).
        withStorage("tx-rollback.db") { storage ->
            val thrown =
                try {
                    storage.withTransaction {
                        storage.sessions.create("session-tx", "tx session", null, null, 10L)
                        storage.messages.append(
                            id = "msg-tx-orphan",
                            sessionId = "session-does-not-exist",
                            turnId = null,
                            role = "user",
                            kind = "text",
                            content = "orphan inside a transaction",
                        )
                    }
                    null
                } catch (e: Throwable) {
                    e
                }
            assertTrue(
                "FK violation inside withTransaction must throw, was: $thrown",
                thrown is android.database.sqlite.SQLiteConstraintException,
            )
            assertTrue(
                "session created before the violation must be rolled back",
                storage.sessions.list().none {
                    it.id ==
                        "session-tx"
                },
            )
        }
    }

    private fun approvalBinding(
        toolCallId: String,
        argsHash: String,
    ): ApprovalBinding =
        ApprovalBinding(
            toolCallId = toolCallId,
            toolName = "bash",
            toolVersion = "1",
            schemaHash = "a".repeat(64),
            contractHash = "b".repeat(64),
            scopeRef = "workspace:test",
            sessionId = "session-mig",
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            uiToken = "ui:test-page",
            argsHash = argsHash,
        )

    /** Column/FK/index facts per table — the full drift surface of a schema export. */
    private data class SchemaFacts(
        val tables: Set<String>,
        val columns: Map<String, List<List<String>>>,
        val foreignKeys: Map<String, List<List<String>>>,
        val indexes: Map<String, List<String>>,
    )

    /** The migrated approvals row's (bindingHash, decision, consumedAt, expiresAt) as strings. */
    private fun approvalColumns(
        sqlite: SupportSQLiteDatabase,
        id: String,
    ): List<String> {
        val cursor =
            sqlite.query(
                "SELECT bindingHash, decision, consumedAt, expiresAt FROM approvals WHERE id = ?",
                arrayOf(id),
            )
        try {
            assertTrue(cursor.moveToFirst())
            return listOf(
                cursor.getString(0),
                cursor.getString(1),
                cursor.getString(2),
                cursor.getString(3),
            )
        } finally {
            cursor.close()
        }
    }

    /** Every migration step after the 1 -> 2 rename must have landed in the live schema. */
    private fun assertLaterMigrationStepsLanded(sqlite: SupportSQLiteDatabase) {
        // The 2 -> 3 step landed: the live schema carries the receipts table.
        assertTrue(
            "v3 upgrade must add interaction_receipts",
            "interaction_receipts" in tables(sqlite),
        )
        // The 3 -> 4 step landed (HXA-049, ADR-0014): the live schema carries the
        // message-attachment relation.
        assertTrue(
            "v4 upgrade must add message_attachments",
            "message_attachments" in tables(sqlite),
        )
        // The 4 -> 5 step landed (HXA-068, ADR-0005): the live schema carries the
        // high-sensitivity egress-rule table.
        assertTrue(
            "v5 upgrade must add high_sensitivity_rules",
            "high_sensitivity_rules" in tables(sqlite),
        )
        assertTrue("v6 upgrade must add a2a_agents", "a2a_agents" in tables(sqlite))
        assertTrue("v6 upgrade must add a2a_capabilities", "a2a_capabilities" in tables(sqlite))
        assertTrue("v7 upgrade must add a2a_tasks", "a2a_tasks" in tables(sqlite))
        // The 16 -> 17 and 17 -> 18 steps were SUPERSEDED at v21 (HXA-209 B4): the legacy
        // tool-approval-preference and new-tool-baseline tables were dropped again, so the full
        // chain leaves no trace of the removed three-state preference chain.
        assertFalse("tool_approval_preferences" in tables(sqlite))
        assertFalse("tool_registration_baseline" in tables(sqlite))
        assertFalse("tool_baseline_meta" in tables(sqlite))
        // The 19 -> 20 step landed (HXA-209 B2, ADR-PERMISSIONS-001): the live schema carries
        // the new session-permission storage — per-session compiled configs, the two-state tool
        // availability and the single-row app default.
        assertTrue(
            "v20 upgrade must add session_permission_configs",
            "session_permission_configs" in tables(sqlite),
        )
        assertTrue("v20 upgrade must add tool_availability", "tool_availability" in tables(sqlite))
        assertTrue(
            "v20 upgrade must add session_permission_defaults",
            "session_permission_defaults" in tables(sqlite),
        )
        // The 21 -> 22 step landed (HXA-209 D2): the live schema carries the additive
        // CUSTOM-draft table (the copied-from preset plus the copied-then-edited rule snapshot).
        assertTrue(
            "v22 upgrade must add session_permission_drafts",
            "session_permission_drafts" in tables(sqlite),
        )
    }

    /**
     * A v20 file in the state a real upgrade has: the legacy preference rows (still present at
     * v20) plus the state [HelixMigrations.MIGRATION_19_20] produced for them — the converted
     * DISABLED rows, the seeded app default and the two baseline tables' rows.
     */
    private fun SupportSQLiteDatabase.seedLegacyToolApprovalTables() {
        seedLegacyApprovalPreferences()
        execSQL(
            "INSERT INTO tool_availability (sourceRef, toolName, scopeKind, scopeRef, state, " +
                "revision, createdAtEpoch, updatedAtEpoch) " +
                "VALUES ('local', 'bash', 'GLOBAL', '', 'DISABLED', 1, 100, 200)",
        )
        execSQL(
            "INSERT INTO tool_availability (sourceRef, toolName, scopeKind, scopeRef, state, " +
                "revision, createdAtEpoch, updatedAtEpoch) " +
                "VALUES ('local', 'write', 'SESSION', 's-1', 'DISABLED', 1, 300, 400)",
        )
        execSQL(
            "INSERT INTO session_permission_defaults (id, mode, configVersion, revision, " +
                "updatedAtEpoch) VALUES ('app', 'READ_ONLY', 1, 0, 0)",
        )
        execSQL(
            "INSERT INTO tool_registration_baseline (sourceRef, toolName, firstSeenVersionCode, " +
                "updatedAtEpoch) VALUES ('local', 'bash', 7, 10)",
        )
        execSQL(
            "INSERT INTO tool_baseline_meta (id, foundingVersionCode, updatedAtEpoch) " +
                "VALUES ('app', 1, 5)",
        )
    }

    /** The legacy table is gone after the drop: any read on it fails with "no such table". */
    private fun assertTableDropped(
        db: SupportSQLiteDatabase,
        table: String,
    ) {
        assertThrows(android.database.SQLException::class.java) {
            db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst() }
        }
    }

    /**
     * The legacy three-state preference rows (two DENY, one ASK, one ALLOW) on a v19 file. Every
     * row has a DISTINCT (tool, scope) key: the legacy table's unique index on
     * (sourceRef, toolName, scopeKind, scopeRef) (HXA-200, MIGRATION_16_17) rejects repeats, so
     * the ASK row keys a different scope from the DENY row for the same tool.
     */
    private fun SupportSQLiteDatabase.seedLegacyApprovalPreferences() {
        execSQL(
            "INSERT INTO tool_approval_preferences (id, sourceRef, toolName, preference, scopeKind, " +
                "scopeRef, contractHash, revision, createdAtEpoch, updatedAtEpoch) " +
                "VALUES ('p-deny-1', 'local', 'bash', 'DENY', 'GLOBAL', '', '', 1, 100, 200)",
        )
        execSQL(
            "INSERT INTO tool_approval_preferences (id, sourceRef, toolName, preference, scopeKind, " +
                "scopeRef, contractHash, revision, createdAtEpoch, updatedAtEpoch) " +
                "VALUES ('p-deny-2', 'local', 'write', 'DENY', 'SESSION', 's-1', '', 2, 300, 400)",
        )
        execSQL(
            "INSERT INTO tool_approval_preferences (id, sourceRef, toolName, preference, scopeKind, " +
                "scopeRef, contractHash, revision, createdAtEpoch, updatedAtEpoch) " +
                "VALUES ('p-ask-1', 'local', 'bash', 'ASK', 'SESSION', 's-2', '', 1, 500, 600)",
        )
        execSQL(
            "INSERT INTO tool_approval_preferences (id, sourceRef, toolName, preference, scopeKind, " +
                "scopeRef, contractHash, revision, createdAtEpoch, updatedAtEpoch) " +
                "VALUES ('p-allow-1', 'local', 'read', 'ALLOW', 'WORKSPACE', 'w-1', 'hash', 1, 700, 800)",
        )
    }

    /**
     * ONLY the DENY identities convert, at the same scope, as DISABLED rows with the original
     * timestamps preserved; ASK/ALLOW rows carry no availability state and produce no rows.
     */
    private fun SupportSQLiteDatabase.assertConvertedToolAvailability() {
        query(
            "SELECT sourceRef, toolName, scopeKind, scopeRef, state, revision, createdAtEpoch, " +
                "updatedAtEpoch FROM tool_availability ORDER BY toolName",
        ).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("local", rows.getString(0))
            assertEquals("bash", rows.getString(1))
            assertEquals("GLOBAL", rows.getString(2))
            assertEquals("", rows.getString(3))
            assertEquals("DISABLED", rows.getString(4))
            assertEquals(1L, rows.getLong(5))
            assertEquals(100L, rows.getLong(6))
            assertEquals(200L, rows.getLong(7))
            assertTrue(rows.moveToNext())
            assertEquals("write", rows.getString(1))
            assertEquals("SESSION", rows.getString(2))
            assertEquals("s-1", rows.getString(3))
            assertEquals("DISABLED", rows.getString(4))
            assertEquals(300L, rows.getLong(6))
            assertEquals(400L, rows.getLong(7))
            assertFalse(rows.moveToNext())
        }
    }

    /** The app default is seeded READ_ONLY at revision 0 — the safest preset on an upgrade. */
    private fun SupportSQLiteDatabase.assertSeededAppDefault() {
        query(
            "SELECT id, mode, configVersion, revision, updatedAtEpoch " +
                "FROM session_permission_defaults",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("app", it.getString(0))
            assertEquals("READ_ONLY", it.getString(1))
            assertEquals(1, it.getInt(2))
            assertEquals(0L, it.getLong(3))
            assertEquals(0L, it.getLong(4))
            assertFalse(it.moveToNext())
        }
    }

    private fun schemaFacts(sqlite: SupportSQLiteDatabase): SchemaFacts {
        val tableNames = tables(sqlite)
        val columns = mutableMapOf<String, List<List<String>>>()
        val foreignKeys = mutableMapOf<String, List<List<String>>>()
        val indexes = mutableMapOf<String, List<String>>()
        tableNames.forEach { table ->
            // name, type, notnull, pk
            columns[table] =
                pragmaRows(sqlite, "PRAGMA table_info($table)").map { row -> listOf(row[1], row[2], row[3], row[5]) }
            // parent table, from column, on-delete action
            foreignKeys[table] =
                pragmaRows(sqlite, "PRAGMA foreign_key_list($table)").map { row -> listOf(row[2], row[3], row[6]) }
            indexes[table] = indexFacts(sqlite, table).sorted()
        }
        return SchemaFacts(tableNames, columns, foreignKeys, indexes)
    }

    private fun pragmaRows(
        sqlite: SupportSQLiteDatabase,
        pragma: String,
    ): List<Array<String>> {
        val rows = mutableListOf<Array<String>>()
        val cursor = sqlite.query(pragma)
        try {
            while (cursor.moveToNext()) {
                val row = arrayOfNulls<String>(cursor.columnCount)
                for (i in 0 until cursor.columnCount) {
                    row[i] = cursor.getString(i)
                }
                @Suppress("UNCHECKED_CAST")
                rows += row as Array<String>
            }
        } finally {
            cursor.close()
        }
        return rows
    }

    private fun indexFacts(
        sqlite: SupportSQLiteDatabase,
        table: String,
    ): List<String> {
        val facts = mutableListOf<String>()
        val cursor =
            sqlite.query(
                "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = ? ORDER BY name",
                arrayOf(table),
            )
        try {
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                // sqlite_autoindex_* entries are schema-derived and carry no SQL; the named
                // (Room-declared) indexes are the drift surface.
                if (name.startsWith("sqlite_autoindex")) continue
                facts += "$name :: ${cursor.getString(1)}"
            }
        } finally {
            cursor.close()
        }
        return facts
    }

    private inline fun withStorage(
        dbName: String,
        block: (HelixStorage) -> Unit,
    ) {
        context.deleteDatabase(dbName)
        File(context.cacheDir, "content-$dbName").deleteRecursively()
        val db = Room.databaseBuilder(context, HelixDatabase::class.java, dbName).build()
        val storage = HelixStorage(db, FileContentStore(File(context.cacheDir, "content-$dbName")), TestSecretStore())
        try {
            block(storage)
        } finally {
            db.close()
        }
    }

    private fun expectedTables(): Set<String> =
        setOf(
            "sessions",
            "messages",
            "turns",
            "model_calls",
            "tool_calls",
            "tool_results",
            "approvals",
            "executions",
            "artifacts",
            "audit_events",
            "provider_configs",
            "runtime_installs",
            "plans",
            "plan_steps",
            "goals",
            "goal_runs",
            "goal_turn_bindings",
            "goal_usage_reservations",
            "mcp_servers",
            "mcp_capabilities",
            "skills",
            "skill_snapshots",
            "capability_grants",
            "execution_targets",
            "interaction_receipts",
            "message_attachments",
            "high_sensitivity_rules",
            "a2a_agents",
            "a2a_capabilities",
            "a2a_tasks",
            // The legacy HXA-200 tables (tool_approval_preferences, tool_registration_baseline,
            // tool_baseline_meta) were DROPPED at v21 (HXA-209 B4): they must NOT appear — a
            // regression that re-adds them turns this guard red.
            "goal_controls",
            // HXA-209 B2 (v19 -> v20, ADR-PERMISSIONS-001): the session-permission storage —
            // the per-session compiled config, the two-state tool availability and the
            // single-row app default. All three must appear in the live schema for the drift
            // guard to pass.
            "session_permission_configs",
            "tool_availability",
            "session_permission_defaults",
            // HXA-209 D2 (v21 -> v22): the additive CUSTOM-draft table — the copied-from
            // preset plus the copied-then-edited rule snapshot. A UI/provenance concern
            // invisible to the execution path, so it has no linearization revision.
            "session_permission_drafts",
        )

    private fun tables(sqlite: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        val cursor = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'")
        try {
            // moveToNext() (not moveToFirst()) — the cursor starts before the first row,
            // and repeating moveToFirst() would loop forever on a non-empty cursor.
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                // sqlite_* is internal, room_master_table is Room's identity table, and
                // android_metadata is a platform artifact — none belong to the doc 9.1 schema.
                if (!name.startsWith("sqlite_") && name != "room_master_table" && name != "android_metadata") {
                    names += name
                }
            }
        } finally {
            cursor.close()
        }
        return names
    }

    private companion object {
        const val EXPORTED_DB = "exported-v1.db"
        const val CODE_DB = "code-v1.db"
        const val FK_DB = "fk-v1.db"
        const val MIGRATION_DB = "migration-1-2.db"
    }
}
