package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.GoalBudgets
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.ProviderProtocol
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalMintOutcome
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.MintRejectionCode
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.mapping.StoredGoal
import com.helix.core.storage.repository.ProviderConfigSpec
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
 * - [v1RejectsSecondApprovalPerToolCall] — the approvals.toolCallId unique index rejects a
 *   second approval for the same tool call;
 * - [v1ProviderDeletionDetachesSessionAndReferencedPlanDeletionIsRejected] — the parent
 *   deletion FK actions: SET NULL detaches a session, NO ACTION protects a referenced plan;
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
    fun v1RejectsSecondApprovalPerToolCall() {
        // approvals.toolCallId is unique (one approval per tool call, doc 9.1): the unique
        // index must reject a second approval row for the same call at runtime.
        withStorage("fk-approval.db") { storage ->
            val session = storage.sessions.create("session-appr", "appr session", null, null, 1L)
            val turn = storage.turns.start("turn-appr-1", session.id, 2L)
            val toolCall = storage.toolCalls.append("toolcall-appr-1", turn.id, "call-a", "bash", "1", "{}", "PENDING")
            val binding = approvalBinding(toolCall.id, "c")
            storage.approvals.create("approval-appr-1", toolCall.id, binding, 1L, 1000L)
            expectConstraintViolation("second approval for the same tool call must fail the unique index") {
                storage.approvals.create("approval-appr-2", toolCall.id, binding, 1L, 1000L)
            }
        }
    }

    @Test
    fun v1ProviderDeletionDetachesSessionAndReferencedPlanDeletionIsRejected() {
        withStorage("fk-setnull.db") { storage ->
            providerConfigSetNullDetachesSession(storage)
            referencedPlanDeletionIsRejected(storage)
        }
    }

    /**
     * sessions.providerId -> provider_configs is SET NULL: deleting the provider config must
     * detach the session (providerId = NULL), not cascade the session row away.
     */
    private fun providerConfigSetNullDetachesSession(storage: HelixStorage) {
        val provider =
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = "provider-setnull-1",
                    displayName = "Setnull provider",
                    protocol = ProviderProtocol.OPENAI_RESPONSES,
                    endpoint = "https://api.example.com/v1",
                    model = "model-1",
                    headersJson = "{}",
                    secretAlias = "alias-setnull-1",
                    capabilitySnapshot = "{}",
                ),
            )
        val session = storage.sessions.create("session-setnull-1", "setnull session", provider.id, "m1", 1L)
        rawSql(storage, "DELETE FROM provider_configs WHERE id = 'provider-setnull-1'")
        assertEquals(
            "session must survive provider deletion with a nulled FK",
            null,
            storage.sessions.resolve(session.id).providerId,
        )
    }

    /**
     * goals.planId -> plans is NO ACTION (not SET NULL): a goal row carries the
     * planId+planHash pair as an invariant, and an FK SET NULL on the id column alone would
     * orphan the hash and make the goal unresolvable. Deleting a plan that a goal still
     * references must therefore fail, and the goal keeps its pair intact.
     */
    private fun referencedPlanDeletionIsRejected(storage: HelixStorage) {
        val plan =
            storage.plans.save(
                PlanArtifact(
                    PlanId("plan-protected-1"),
                    "protected plan",
                    emptyList(),
                    listOf(PlanStep("step one", "do the thing")),
                    listOf("it works"),
                    emptyList(),
                    1,
                ),
                "DRAFT",
                null,
            )
        val goal =
            StoredGoal(
                id = "goal-protected-1",
                objective = "protected goal",
                criteria = emptyList(),
                budgets = GoalBudgets(4, 8, 1000L, 60_000L, 10_000L, 1),
                state = "DRAFT",
                planId = plan.id,
                planHash = "f".repeat(64),
                nextCheckpoint = null,
                correlationId = "corr-protected-1",
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
        expectConstraintViolation("deleting a plan referenced by a goal must fail the NO ACTION FK") {
            rawSql(storage, "DELETE FROM plans WHERE id = 'plan-protected-1'")
        }
        val resolved = storage.goals.resolve(goal.id)
        assertEquals("goal must keep its plan reference after the rejected delete", plan.id, resolved.planId)
        assertEquals("f".repeat(64), resolved.planHash)
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

    private fun approvalBinding(
        toolCallId: String,
        digestSeed: String,
    ): ApprovalBinding =
        ApprovalBinding(
            toolCallId = toolCallId,
            toolName = "bash",
            toolVersion = "1",
            schemaHash = digestSeed + "0".repeat(63),
            contractHash = digestSeed + "2".repeat(63),
            scopeRef = "workspace:test",
            sessionId = "session-guards",
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            uiToken = "ui:test-page",
            argsHash = digestSeed + "1".repeat(63),
        )

    /** approvals: decide is one-time; only APPROVED mints a proof; the proof is consumed once. */
    private fun guardsForApprovals(
        storage: HelixStorage,
        toolCall: ToolCallEntity,
    ) {
        approvalDecideAndConsumeAreOneTime(storage, toolCall)
        // approvals.toolCallId is unique (one approval per tool call), so the
        // consume-before-decide probe needs its own tool call.
        pendingCannotBeConsumedOrForged(storage)
        deniedCannotBeConsumed(storage)
    }

    /** decide is one-time; an APPROVED record mints exactly one proof, consumed exactly once. */
    private fun approvalDecideAndConsumeAreOneTime(
        storage: HelixStorage,
        toolCall: ToolCallEntity,
    ) {
        val approval =
            storage.approvals.create(
                "approval-guards-1",
                toolCall.id,
                approvalBinding(toolCall.id, "a"),
                10L,
                100000L,
            )
        storage.approvals.decide(approval.id, ApprovalDecision.APPROVED, 10L)
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.decide(approval.id, ApprovalDecision.DENIED, 11L)
        }
        val minted = storage.approvals.mint(approval.id, 12L)
        val proof = (minted as ApprovalMintOutcome.Minted).proof
        storage.approvals.consume(proof, 12L, 12L)
        val reConsume = ApprovalProof(approval.id, proof.bindingHash)
        assertThrows(IllegalArgumentException::class.java) { storage.approvals.consume(reConsume, 13L, 13L) }
    }

    /** pending records mint nothing; a forged proof fails the SQL guard. */
    private fun pendingCannotBeConsumedOrForged(storage: HelixStorage) {
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
        val pendingApproval =
            storage.approvals.create(
                "approval-guards-2",
                secondCall.id,
                approvalBinding(secondCall.id, "b"),
                10L,
                100000L,
            )
        // pending: no proof exists to consume — minting is rejected, and a forged proof fails
        // the SQL guard (decision is not APPROVED).
        assertEquals(
            ApprovalMintOutcome.Rejected(MintRejectionCode.PENDING),
            storage.approvals.mint(pendingApproval.id, 14L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(ApprovalProof(pendingApproval.id, pendingApproval.bindingHash), 14L, 14L)
        }
        assertEquals(0, storage.database.approvalDao().decide(pendingApproval.id, "ALLOWED", 15L))
        assertEquals(null, storage.approvals.resolve(pendingApproval.id).decision)
    }

    /** DENIED is a processed decision, not a credential: no mint, no consume. */
    private fun deniedCannotBeConsumed(storage: HelixStorage) {
        val deniedCall =
            storage.toolCalls.append(
                "toolcall-guards-3",
                "turn-guards-1",
                "call-g3",
                "bash",
                "1",
                "{}",
                "PENDING",
            )
        val deniedApproval =
            storage.approvals.create(
                "approval-guards-3",
                deniedCall.id,
                approvalBinding(deniedCall.id, "d"),
                10L,
                100000L,
            )
        storage.approvals.decide(deniedApproval.id, ApprovalDecision.DENIED, 16L)
        // DENIED is a processed decision, not a credential: minting and consuming both fail.
        assertEquals(
            ApprovalMintOutcome.Rejected(MintRejectionCode.DENIED),
            storage.approvals.mint(deniedApproval.id, 17L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(ApprovalProof(deniedApproval.id, deniedApproval.bindingHash), 17L, 17L)
        }
        assertEquals(null, storage.approvals.resolve(deniedApproval.id).consumedAt)
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

    /**
     * Schema-level probe on Room's own connection (the generated database enables
     * `PRAGMA foreign_keys = ON` there, asserted by v1EnforcesForeignKeysAtRuntime). Used only
     * to exercise FK actions (SET NULL / NO ACTION) that the repositories deliberately do not
     * expose — production code never deletes sessions or their parents.
     */
    private fun rawSql(
        storage: HelixStorage,
        sql: String,
    ) {
        storage.database.openHelper.writableDatabase
            .execSQL(sql)
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
        val storage = HelixStorage(db, FileContentStore(File(context.cacheDir, "content-$dbName")), TestSecretStore())
        try {
            block(storage)
        } finally {
            db.close()
        }
    }
}
