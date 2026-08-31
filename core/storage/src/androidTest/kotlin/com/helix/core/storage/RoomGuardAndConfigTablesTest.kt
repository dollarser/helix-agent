package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.GoalBudgets
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.mapping.StoredGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v1 schema enforcement fixtures added with the post-HXA-014 review (kept separate from
 * [RoomMigrationFixtureTest] so each class stays focused):
 *
 * - [v1OneTimeGuardsRejectRepeatedTransitions] — decide/consume/markVerified/archive/finish
 *   are each one-time and throw on a repeated transition;
 * - [v1RejectsOrphanReferencesAndDuplicateExecutions] — the sessions/goals FKs and the
 *   executions unique index reject rows that violate them at runtime;
 * - [v1RoundTripsRuntimeMcpSkillAndGrantTables] — the config tables (runtime installs,
 *   execution targets, capability grants, MCP servers+capabilities, skills+snapshots)
 *   round-trip through the repositories.
 */
@RunWith(AndroidJUnit4::class)
class RoomGuardAndConfigTablesTest {
    private lateinit var context: Context

    @Test
    fun v1OneTimeGuardsRejectRepeatedTransitions() {
        withStorage("guards.db") { storage ->
            val toolCall = seedToolCall(storage)
            guardsForApprovals(storage, toolCall)
            guardsForToolResultsAndSessions(storage, toolCall)
            guardsForGoalRuns(storage)
        }
    }

    @Test
    fun v1RejectsOrphanReferencesAndDuplicateExecutions() {
        withStorage("fk-ref.db") { storage ->
            // sessions.providerId must reference provider_configs (or be null).
            expectConstraintViolation("orphan sessions.providerId must fail the FK") {
                storage.sessions.create("session-orphan-provider", "orphan", "provider-does-not-exist", "m1", 1L)
            }
            // goals.planId must reference plans (or be null).
            expectConstraintViolation("orphan goals.planId must fail the FK") { storage.goals.save(orphanPlanGoal()) }
            // goal_runs.goalId must reference goals (or be null).
            expectConstraintViolation("orphan goal_runs.goalId must fail the FK") {
                storage.goalRuns.open("goalrun-orphan-1", "goal-does-not-exist", "USER_OPEN", 1L)
            }
            duplicateExecutionRejected(storage)
        }
    }

    @Test
    fun v1RoundTripsRuntimeMcpSkillAndGrantTables() {
        // The seven tables without a dedicated round-trip test before: runtime installs,
        // execution targets, capability grants, MCP servers+capabilities, skills+snapshots.
        withStorage("crud-config-tables.db") { storage ->
            val hash = "b".repeat(64)
            roundTripRuntimeAndGrantTables(storage, hash)
            roundTripMcpTables(storage, hash)
            roundTripSkillTables(storage, hash)
        }
    }

    private fun seedToolCall(storage: HelixStorage): ToolCallEntity {
        val session = storage.sessions.create("session-guards", "guards session", null, null, 1L)
        val turn = storage.turns.start("turn-guards-1", session.id, 2L)
        return storage.toolCalls.append("toolcall-guards-1", turn.id, "call-g", "bash", "1", "{}", "PENDING")
    }

    /** approvals: decide is one-time; consume requires a decision and is one-time. */
    private fun guardsForApprovals(
        storage: HelixStorage,
        toolCall: ToolCallEntity,
    ) {
        val hash = "a".repeat(64)
        val approval = storage.approvals.create("approval-guards-1", toolCall.id, hash)
        storage.approvals.decide(approval.id, "ALLOWED", 10L)
        assertThrows(IllegalArgumentException::class.java) { storage.approvals.decide(approval.id, "DENIED", 11L) }
        storage.approvals.consume(approval.id, 12L)
        assertThrows(IllegalArgumentException::class.java) { storage.approvals.consume(approval.id, 13L) }
        // approvals.toolCallId is unique (one approval per tool call), so the
        // consume-before-decide probe needs its own tool call.
        val secondCall =
            storage.toolCalls.append(
                "toolcall-guards-2",
                "turn-guards-1",
                "call-g2",
                "bash",
                "1",
                "{}",
                "PENDING",
            )
        val pendingApproval = storage.approvals.create("approval-guards-2", secondCall.id, hash)
        assertThrows(IllegalArgumentException::class.java) { storage.approvals.consume(pendingApproval.id, 14L) }
    }

    /** tool results: verification is one-time; sessions: archive is one-time. */
    private fun guardsForToolResultsAndSessions(
        storage: HelixStorage,
        toolCall: ToolCallEntity,
    ) {
        val result =
            storage.toolResults.append(
                id = "toolresult-guards-1",
                toolCallId = toolCall.id,
                status = "SUCCESS",
                summary = "ok",
                content = "body",
            )
        storage.toolResults.markVerified(result)
        assertThrows(IllegalArgumentException::class.java) { storage.toolResults.markVerified(result) }
        storage.sessions.archive("session-guards", 20L)
        assertThrows(IllegalArgumentException::class.java) { storage.sessions.archive("session-guards", 21L) }
    }

    /** goal runs: finish is one-time. */
    private fun guardsForGoalRuns(storage: HelixStorage) {
        val goal =
            StoredGoal(
                id = "goal-guards-1",
                objective = "guards goal",
                criteria = emptyList(),
                budgets = GoalBudgets(4, 8, 1000L, 60_000L, 10_000L, 1),
                state = "RUNNING",
                planId = null,
                planHash = null,
                nextCheckpoint = null,
                correlationId = "corr-guards-1",
                runCount = 1,
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
        val run = storage.goalRuns.open("goalrun-guards-1", goal.id, "USER_OPEN", 30L)
        storage.goalRuns.finish(run, "SUCCESS", 40L, 5L, 1, 2, 30L)
        assertThrows(IllegalArgumentException::class.java) { storage.goalRuns.finish(run, "FAILED", 41L, 5L, 0, 0, 0L) }
    }

    private fun orphanPlanGoal(): StoredGoal =
        StoredGoal(
            id = "goal-orphan-plan",
            objective = "orphan plan ref",
            criteria = emptyList(),
            budgets = GoalBudgets(4, 8, 1000L, 60_000L, 10_000L, 1),
            state = "DRAFT",
            // StoredGoal requires planId and planHash together; the hash value is irrelevant
            // to the FK probe (the FK is on planId -> plans.id).
            planId = "plan-does-not-exist",
            planHash = "f".repeat(64),
            nextCheckpoint = null,
            correlationId = "corr-orphan-1",
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

    /** executions.toolCallId is unique: one execution row per tool call. */
    private fun duplicateExecutionRejected(storage: HelixStorage) {
        val session = storage.sessions.create("session-exec", "exec session", null, null, 1L)
        val turn = storage.turns.start("turn-exec-1", session.id, 2L)
        val toolCall = storage.toolCalls.append("toolcall-exec-1", turn.id, "call-e", "bash", "1", "{}", "PENDING")
        storage.executions.register("execution-exec-1", toolCall.id, "quickjs", "{}")
        expectConstraintViolation("second execution for the same tool call must fail the unique index") {
            storage.executions.register("execution-exec-2", toolCall.id, "quickjs", "{}")
        }
    }

    private fun expectConstraintViolation(
        description: String,
        block: () -> Unit,
    ) {
        val thrown =
            try {
                block()
                null
            } catch (e: Throwable) {
                e
            }
        if (thrown == null) {
            fail("$description — but no exception was thrown (row accepted)")
        }
        assertTrue(
            "$description — expected SQLiteConstraintException, was $thrown",
            thrown is android.database.sqlite.SQLiteConstraintException,
        )
    }

    private fun roundTripRuntimeAndGrantTables(
        storage: HelixStorage,
        hash: String,
    ) {
        val install =
            storage.runtimeInstalls.register(
                id = "install-crud-1",
                type = "quickjs",
                version = "2025-04-11",
                state = "READY",
                manifestHash = hash,
                installedAt = 1L,
            )
        storage.runtimeInstalls.updateState(install, "DEGRADED")
        assertEquals("DEGRADED", storage.runtimeInstalls.resolve(install.id).state)
        val target =
            storage.executionTargets.register(
                id = "target-crud-1",
                type = "quickjs",
                descriptor = """{"runtime":"quickjs","limits":"default"}""",
                capabilitySnapshot = "{}",
            )
        assertEquals("quickjs", storage.executionTargets.resolve(target.id).type)
        val grant = storage.capabilityGrants.record("root", "GRANTED", "user:demo", 2L)
        assertEquals("GRANTED", storage.capabilityGrants.resolve(grant.rowId).systemState)
        assertEquals(1, storage.capabilityGrants.listByType("root").size)
    }

    private fun roundTripMcpTables(
        storage: HelixStorage,
        hash: String,
    ) {
        val server =
            storage.mcpServers.register(
                id = "mcp-crud-1",
                transport = "stdio",
                endpointRef = null,
                commandRef = "mcp-servers/demo.json",
                authAlias = "mcp-alias-1",
                trustState = "UNTRUSTED",
            )
        // HXA-071: registered disabled by default.
        assertEquals(false, storage.mcpServers.resolve(server.id).enabled)
        storage.mcpServers.update(server, true, "TRUSTED")
        val enabled = storage.mcpServers.resolve(server.id)
        assertEquals(true, enabled.enabled)
        assertEquals("TRUSTED", enabled.trustState)
        val capability =
            storage.mcpCapabilities.register(
                serverId = server.id,
                protocolVersion = "2025-06-18",
                kind = "tool",
                name = "demo.tool",
                schemaHash = hash,
                enabled = true,
            )
        assertEquals(1, storage.mcpCapabilities.listByServer(server.id).size)
        storage.mcpCapabilities.setEnabled(capability.rowId, false)
        assertEquals(
            false,
            storage.mcpCapabilities
                .listByServer(server.id)
                .single()
                .enabled,
        )
    }

    private fun roundTripSkillTables(
        storage: HelixStorage,
        hash: String,
    ) {
        val skill =
            storage.skills.register(
                id = "skill-crud-1",
                name = "demo-skill",
                source = "user",
                version = "1.0.0",
                rootRef = "skills/demo-skill",
                contentHash = hash,
            )
        assertEquals(true, storage.skills.resolve(skill.id).enabled)
        storage.skills.setEnabled(skill.id, false)
        assertEquals(false, storage.skills.resolve(skill.id).enabled)
        val snapshot =
            storage.skillSnapshots.record(
                runId = "goalrun-crud-snap",
                skillId = skill.id,
                contentHash = hash,
                catalogEntry = "skills/demo-skill/1.0.0",
            )
        assertEquals("skill-crud-1", snapshot.skillId)
    }

    private inline fun withStorage(
        dbName: String,
        block: (HelixStorage) -> Unit,
    ) {
        context = ApplicationProvider.getApplicationContext()
        // Fresh fixture per run: installed APKs keep app data between connected-test runs,
        // so stale rows would break the assertions.
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
}
