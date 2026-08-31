package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.criteria.StoredCriterion
import com.helix.core.storage.mapping.StoredGoal
import com.helix.core.storage.repository.ProviderConfigSpec
import org.junit.Assert.assertEquals
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
 * - [v1ExportMatchesTheCodeBuiltSchema] closes the drift loop (the JVM contract test
 *   checks the export against doc 9.1; this test checks the code against the export);
 * - [v1EnforcesForeignKeysAtRuntime] proves the runtime schema enables FK enforcement;
 * - the CRUD round-trip and FK-violation tests exercise the v1 fixture end to end.
 *
 * Future schema changes add a `Migration` object plus a new exported version here
 * (doc 9.2: migrations require a schema export and an instrumentation test).
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
        listOf(EXPORTED_DB, CODE_DB, FK_DB).forEach { context.deleteDatabase(it) }
        helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                HelixDatabase::class.java,
            )
    }

    @Test
    fun v1ExportExistsAsATestAsset() {
        val versions = context.assets.list("com.helix.core.storage.HelixDatabase")
        assertTrue("schema export missing from assets: ${versions?.toList()}", versions?.contains("1.json") == true)
    }

    @Test
    fun v1ExportMatchesTheCodeBuiltSchema() {
        val exportedDb = helper.createDatabase(EXPORTED_DB, 1)
        val exported = schemaFacts(exportedDb)
        exportedDb.close()

        val codeDb = Room.databaseBuilder(context, HelixDatabase::class.java, CODE_DB).build()
        try {
            val code = schemaFacts(codeDb.openHelper.writableDatabase)
            assertEquals(
                "code-built v1 schema must match the exported v1 schema",
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
                    "provider-crud",
                    "Crud Provider",
                    "openai-responses",
                    "http://localhost:1",
                    "model-1",
                    "{}",
                    "alias-only",
                    "{}",
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

            val approval = storage.approvals.create("approval-crud-1", toolCall.id, toolCall.argsHash)
            storage.approvals.decide(approval.id, ApprovalDecision.APPROVED, 40L)
            storage.approvals.consume(approval.id, 50L)
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
                    "provider-crud-1",
                    "Local Provider",
                    "openai-responses",
                    "http://localhost:1",
                    "m1",
                    "{}",
                    "alias-only-no-secret",
                    "{}",
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

    /** Column/FK/index facts per table — the full drift surface of a schema export. */
    private data class SchemaFacts(
        val tables: Set<String>,
        val columns: Map<String, List<List<String>>>,
        val foreignKeys: Map<String, List<List<String>>>,
        val indexes: Map<String, List<String>>,
    )

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
        val storage = HelixStorage(db, FileContentStore(File(context.cacheDir, "content-$dbName")))
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
            "mcp_servers",
            "mcp_capabilities",
            "skills",
            "skill_snapshots",
            "capability_grants",
            "execution_targets",
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
    }
}
