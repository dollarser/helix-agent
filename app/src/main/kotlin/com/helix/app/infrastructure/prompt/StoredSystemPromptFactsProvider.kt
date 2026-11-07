package com.helix.app.infrastructure.prompt

import com.helix.app.application.context.GoalPromptFacts
import com.helix.app.application.context.ProjectPromptFacts
import com.helix.app.application.context.SystemPromptFacts
import com.helix.app.application.context.SystemPromptRequest
import com.helix.app.memory.MemoryService
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath

/** Reads authorized facts once per model request, outside the pure prompt registry mapping. */
internal class StoredSystemPromptFactsProvider(
    private val storage: HelixStorage,
    private val projectInstructionsReader: (FileScopePath) -> String,
    private val memory: MemoryService? = null,
) {
    fun collect(request: SystemPromptRequest): SystemPromptFacts {
        val project = storage.projects.forSession(request.sessionId)
        val permissions =
            storage.sessionPermissionConfigs.forSession(request.sessionId)
                ?: storage.sessionPermissionConfigs.appDefault()
        val memorySnapshot =
            if (permissions.ruleFor(OperationEffect.FILE_READ_EXTERNAL) == OperationRule.ALLOW) {
                memory?.contextSnapshot(request.sessionId)
            } else {
                null
            }
        val goal = activeGoal(request)
        val workspacePermissions =
            storage.sessionPermissionConfigs.forSession(request.sessionId)
                ?: storage.sessionPermissionConfigs.appDefault()
        val projectInstructions =
            if (workspacePermissions.ruleFor(OperationEffect.FILE_READ_WORKSPACE) == OperationRule.ALLOW) {
                projectInstructionsReader(request.directory)
            } else {
                ""
            }
        return SystemPromptFacts(
            request = request,
            project = project?.let { ProjectPromptFacts(it.id, it.revision, it.name, it.description, it.instructions) },
            memory = memorySnapshot,
            projectInstructions = projectInstructions,
            goal = goal,
        )
    }

    @Suppress("ReturnCount") // Only the request's active durable binding can contribute a goal.
    private fun activeGoal(request: SystemPromptRequest): GoalPromptFacts? {
        val turn =
            request.turnId?.let(storage.turns::resolve)
                ?: storage.turns.listBySession(request.sessionId).lastOrNull() ?: return null
        require(turn.sessionId == request.sessionId) { "System prompt turn belongs to another session" }
        if (turn.endedAt != null) return null
        val binding = storage.goalTurnBindings.byTurn(turn.id) ?: return null
        val run = storage.goalRuns.resolve(binding.runId)
        if (run.endedAt != null) return null
        val goal = storage.goals.resolve(run.goalId)
        if (goal.state != "RUNNING") return null
        return GoalPromptFacts(
            turnId = turn.id,
            runId = run.id,
            goalId = goal.id,
            objective = goal.objective,
            criteria = goal.criteria.map { it.description },
            plan = goal.planId?.let(storage.plans::resolveOrNull),
        )
    }
}
