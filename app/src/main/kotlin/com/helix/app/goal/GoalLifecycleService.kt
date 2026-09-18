package com.helix.app.goal

import com.helix.app.chat.GoalRunCoordinator
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.model.Clock
import com.helix.core.model.GoalBudgets
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.GoalControlEntity
import com.helix.core.storage.mapping.StoredGoal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Edits are CAS-protected and settle between rounds. No persisted edit is authority to wake a process. */
@Suppress("TooManyFunctions") // One owner for transactional create/read/edit, ownership and settlement.
internal class GoalLifecycleService(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val ids: () -> String,
    private val authorize: (ExecutableToolCall, String) -> GoalBudgets?,
    private val staged: (String, String, String, Boolean?) -> Unit,
    private val armed: (String, String) -> Boolean = { _, _ -> false },
) {
    @Suppress("SwallowedException") // Invalid model arguments become a bounded tool rejection.
    fun execute(call: ExecutableToolCall): ToolExecutorResult {
        if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
        return try {
            var output: JsonObject? = null
            storage.withTransaction {
                val turn = storage.turns.resolve(requireNotNull(call.turnId))
                require(turn.sessionId == call.sessionId && turn.endedAt == null) { "No owned live turn" }
                output =
                    when (call.toolName) {
                        "get_goal" -> {
                            (
                                storage.goalTurnBindings.byTurn(turn.id)?.let { binding ->
                                    storage.goals.resolve(storage.goalRuns.resolve(binding.runId).goalId)
                                } ?: current(requireNotNull(call.sessionId))
                            )?.let(::snapshot)
                                ?: buildJsonObject { put("goal", JsonNull) }
                        }

                        "create_goal" -> {
                            create(call)
                        }

                        "update_goal" -> {
                            update(call)
                        }

                        else -> {
                            error("Unsupported Goal operation")
                        }
                    }
            }
            ToolExecutorResult.Completed(requireNotNull(output))
        } catch (error: IllegalArgumentException) {
            ToolExecutorResult.Failed(error.message ?: "Goal request rejected", sideEffectFree = true)
        }
    }

    fun bind(
        goalId: String,
        sessionId: String,
    ) {
        // Reject a stale/deleted Goal through the caller's normal domain-error path,
        // before a control-row insert can fail with an uncaught Room foreign-key error.
        storage.goals.resolve(goalId)
        val existing = storage.goalControls.find(goalId)
        if (existing == null) {
            require(storage.goalTurnBindings.sessionForGoal(goalId).let { it == null || it == sessionId })
            storage.goalControls.insert(GoalControlEntity(goalId, sessionId, 0, null, null))
        } else {
            require(existing.sessionId == sessionId) { "Goal belongs to another session" }
        }
    }

    fun current(sessionId: String): StoredGoal? {
        val owned = storage.goalControls.bySession(sessionId).map { storage.goals.resolve(it.goalId) }
        val unfinished = owned.filter { it.state !in TERMINAL }
        val ids = unfinished.map { it.id }.toSet()
        val latest =
            storage.turns.listBySession(sessionId).asReversed().firstNotNullOfOrNull { turn ->
                storage.goalTurnBindings.byTurn(turn.id)?.let { binding ->
                    storage.goalRuns
                        .resolve(binding.runId)
                        .goalId
                        .takeIf { it in ids }
                }
            }
        return unfinished.firstOrNull { it.id == latest } ?: unfinished.firstOrNull() ?: owned.firstOrNull()
    }

    private fun create(call: ExecutableToolCall): JsonObject {
        val defaults = requireUser(call)
        val session = requireNotNull(call.sessionId)
        require(current(session)?.state.let { it == null || it in TERMINAL }) {
            "An unfinished Goal already exists; read and update it instead"
        }
        val objective =
            call.args
                .getValue("objective")
                .jsonPrimitive.content
        require(objective.isNotBlank() && objective.length <= com.helix.core.agent.Goal.MAX_OBJECTIVE_LENGTH)
        val budgets = budgets(call.args["budgets"]?.jsonObject, defaults)
        val id = GoalRunCoordinator(storage, clock, ids).saveReadyGoal(objective, emptyList(), budgets, null, null)
        bind(id, session)
        val pending = buildJsonObject { put("status", "active") }
        check(storage.goalControls.stage(id, 0, call.turnId, pending.toString()) == 1)
        audit(id, "goal.model_created")
        staged(session, requireNotNull(call.turnId), id, true)
        return snapshot(storage.goals.resolve(id))
    }

    private fun report(
        call: ExecutableToolCall,
        id: String,
        status: String?,
    ): JsonObject {
        require(status in setOf("complete", "in_progress", "blocked")) { "Provide a change or report" }
        require(
            call.args["summary"]
                ?.jsonPrimitive
                ?.content
                ?.isNotBlank() == true,
        ) { "Provide a summary" }
        val binding = storage.goalTurnBindings.byTurn(requireNotNull(call.turnId))
        require(
            binding != null &&
                storage.goalRuns.resolve(binding.runId).let {
                    it.goalId == id && it.endedAt == null
                },
        ) { "Reports require this turn's active Goal" }
        require(
            status != "complete" ||
                storage.goalUsageReservations
                    .pendingForRun(binding.runId)
                    .none { it.kind == "TIME_LEASE" },
        ) { "Collect the pending Job before reporting completion" }
        return call.args
    }

    private fun update(call: ExecutableToolCall): JsonObject {
        val id =
            call.args
                .getValue("id")
                .jsonPrimitive.content
        val control = requireNotNull(storage.goalControls.find(id)) { "Read the current Goal first" }
        require(control.sessionId == call.sessionId) { "Goal belongs to another session" }
        require(
            control.revision ==
                call.args
                    .getValue("expected_revision")
                    .jsonPrimitive.long,
        ) {
            "Goal changed; read get_goal again"
        }
        require(control.pendingJson == null) { "A change is already pending settlement" }
        val goal = storage.goals.resolve(id)
        require(goal.state !in TERMINAL) { "Goal is terminal; create a new Goal" }
        val status = call.args["status"]?.jsonPrimitive?.content
        val edit = "objective" in call.args || "budgets" in call.args || status in setOf("active", "paused")
        if (!edit) return report(call, id, status)
        requireUser(call)
        require(status == null || status in setOf("active", "paused")) { "Report separately from editing" }
        require(goal.planId == null || "objective" !in call.args) { "Edit and review the bound plan first" }
        call.args["objective"]?.jsonPrimitive?.content?.let {
            require(it.isNotBlank() && it.length <= com.helix.core.agent.Goal.MAX_OBJECTIVE_LENGTH) {
                "Objective must be 1..16384 nonblank characters"
            }
        }
        budgets(call.args["budgets"]?.jsonObject, goal.budgets)
        check(storage.goalControls.stage(id, control.revision, call.turnId, call.args.toString()) == 1)
        audit(id, "goal.edit_staged")
        val activate =
            when (status) {
                "active" -> true
                "paused" -> false
                else -> null
            }
        staged(requireNotNull(call.sessionId), requireNotNull(call.turnId), id, activate)
        return snapshot(goal)
    }

    /** Called after durable turn settlement, under the host's admission gate. */
    fun settle(turnId: String) {
        storage.withTransaction {
            val turn = storage.turns.resolve(turnId)
            storage.goalControls.pendingForTurn(turnId).forEach { control ->
                val goal = storage.goals.resolve(control.goalId)
                val eligible =
                    turn.state == "COMPLETED" && turn.pauseRequestedAt == null &&
                        goal.state !in TERMINAL && !storage.goalTurnBindings.hasUnsettledCalls(goal.id)
                val applied = eligible && applyEdit(goal, control)
                check(storage.goalControls.settle(control.goalId, control.revision) == 1)
                audit(goal.id, if (applied) "goal.edit_applied" else "goal.edit_discarded")
                if (!applied) staged(control.sessionId, turnId, goal.id, false)
            }
        }
    }

    private fun applyEdit(
        goal: StoredGoal,
        control: GoalControlEntity,
    ): Boolean {
        val pending = Json.parseToJsonElement(requireNotNull(control.pendingJson)).jsonObject
        if ("budgets" in pending) {
            val next =
                GoalReducer.reduce(
                    goal.toRuntimeGoal(),
                    GoalEvent.BudgetsUpdated(budgets(pending["budgets"]?.jsonObject, goal.budgets)),
                )
            if (next.ignored) return false
            storage.goals.updateGoal(next.state.toStoredGoal())
        }
        pending["objective"]?.jsonPrimitive?.content?.let { storage.goals.updateObjective(goal.id, it) }
        if (pending["status"]?.jsonPrimitive?.content == "active" && goal.state == "BLOCKED") {
            com.helix.app.chat
                .GoalBlockerResolution(storage, clock, ids)
                .resolve(goal.id, control.sessionId, contextFits = false)
        }
        return true
    }

    private fun requireUser(call: ExecutableToolCall): GoalBudgets {
        val quote =
            call.args["user_request"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        require(quote.isNotBlank()) { "Quote the current human's direct request" }
        return requireNotNull(authorize(call, quote)) {
            "This is not a direct human request; automatic rounds cannot edit"
        }
    }

    private fun snapshot(goal: StoredGoal): JsonObject =
        buildJsonObject {
            put(
                "goal",
                buildJsonObject {
                    put("id", goal.id)
                    put("objective", goal.objective)
                    put("state", goal.state)
                    val control = requireNotNull(storage.goalControls.find(goal.id))
                    put("revision", control.revision)
                    put("armed", armed(control.sessionId, goal.id))
                    put("pending_change", control.pendingJson?.let(Json::parseToJsonElement) ?: JsonNull)
                    put(
                        "last_outcome",
                        storage.goalRuns
                            .listByGoal(goal.id)
                            .lastOrNull()
                            ?.outcome
                            ?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull,
                    )
                    put("rounds_started", goal.runCount)
                    put("model_calls", goal.modelCalls)
                    put("tool_calls", goal.toolCalls)
                    put("tokens", goal.totalTokens)
                    put("runtime_millis", goal.runTimeMillis)
                    put("max_model_calls", goal.budgets.maxModelCalls)
                    put("max_tool_calls", goal.budgets.maxToolCalls)
                    put("max_tokens", goal.budgets.maxTotalTokens)
                    put("max_runtime_millis", goal.budgets.maxDurationMillis)
                    put("max_round_millis", goal.budgets.maxWakeDurationMillis)
                    put("max_retries", goal.budgets.maxRetries)
                },
            )
        }

    private fun audit(
        id: String,
        type: String,
    ) {
        storage.auditEvents.append(
            ids(),
            storage.goals.resolve(id).correlationId,
            type,
            "USER",
            """{"revision":${storage.goalControls.find(id)?.revision}}""",
            clock.now().toEpochMilli(),
        )
    }

    private fun budgets(
        args: JsonObject?,
        previous: GoalBudgets,
    ): GoalBudgets =
        previous.copy(
            maxModelCalls = args?.get("max_model_calls")?.jsonPrimitive?.int ?: previous.maxModelCalls,
            maxToolCalls = args?.get("max_tool_calls")?.jsonPrimitive?.int ?: previous.maxToolCalls,
            maxTotalTokens = args?.get("max_tokens")?.jsonPrimitive?.long ?: previous.maxTotalTokens,
            maxDurationMillis = args?.get("max_runtime_millis")?.jsonPrimitive?.long ?: previous.maxDurationMillis,
            maxWakeDurationMillis =
                args?.get("max_round_millis")?.jsonPrimitive?.long
                    ?: previous.maxWakeDurationMillis,
            maxRetries = args?.get("max_retries")?.jsonPrimitive?.int ?: previous.maxRetries,
        )

    private companion object {
        val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED")
    }
}
