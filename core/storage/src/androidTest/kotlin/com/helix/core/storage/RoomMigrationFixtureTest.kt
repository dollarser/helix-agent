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
 * - the export/code drift loop is closed by [v5ExportMatchesTheCodeBuiltSchema] (the live
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
    fun v5ExportMatchesTheCodeBuiltSchema() {
        val exportedDb = helper.createDatabase("v5-export.db", 5)
        val exported = schemaFacts(exportedDb)
        exportedDb.close()

        val codeDb = Room.databaseBuilder(context, HelixDatabase::class.java, CODE_DB).build()
        try {
            val code = schemaFacts(codeDb.openHelper.writableDatabase)
            assertEquals(
                "code-built v5 schema must match the exported v5 schema",
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
        // Room opens the v1 file and applies the FULL committed chain (1 -> 2 -> 3 -> 4 -> 5) —
        // the exact production path (HelixStorage registers the same set; including the
        // room_master_table identity update). The assertions below verify the 1 -> 2 step
        // specifically; the chain also proves 2 -> 3 (interaction_receipts), 3 -> 4
        // (message_attachments) and 4 -> 5 (high_sensitivity_rules) all applied.
        val roomDb =
            Room
                .databaseBuilder(context, HelixDatabase::class.java, MIGRATION_DB)
                .addMigrations(
                    HelixDatabase.MIGRATION_1_2,
                    HelixDatabase.MIGRATION_2_3,
                    HelixDatabase.MIGRATION_3_4,
                    HelixDatabase.MIGRATION_4_5,
                ).build()
        try {
            val sqlite = roomDb.openHelper.writableDatabase
            val columns =
                pragmaRows(sqlite, "PRAGMA table_info(approvals)").map { row -> row[1] }.toSet()
            assertTrue(
                "v2 approvals must carry bindingHash + expiresAt and drop argsHash: $columns",
                "bindingHash" in columns && "expiresAt" in columns && "argsHash" !in columns,
            )

            fun row(id: String): List<String> {
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
            // Rows survive the migration; the hash content is preserved under the new name.
            assertEquals(listOf("p".repeat(64), null, null, "0"), row("approval-mig-1"))
            assertEquals(listOf("q".repeat(64), "APPROVED", "20", "0"), row("approval-mig-2"))
            // expiresAt = 0: every migrated approval is already expired (fail closed) — the
            // old APPROVED row can never consume a proof post-migration (SQL guard).
            assertEquals(0, roomDb.approvalDao().consumeByBinding("approval-mig-2", "q".repeat(64), 30L, 30L))
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
            "mcp_servers",
            "mcp_capabilities",
            "skills",
            "skill_snapshots",
            "capability_grants",
            "execution_targets",
            "interaction_receipts",
            "message_attachments",
            "high_sensitivity_rules",
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
