from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalReportTool.kt')
p.write_text('''package com.helix.app.goal

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.*
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.seconds

/** Model-owned semantic report. The host validates ownership, never certifies its truth. */
internal object GoalReportTool {
    const val NAME = "goal.report"
    private val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"status":{"type":"string","enum":["complete","in_progress","blocked"]},"summary":{"type":"string","minLength":1,"maxLength":4096}},"required":["status","summary"],"additionalProperties":false}""",
    ).jsonObject

    fun register(registry: ToolRegistry, implementations: ToolImplementationRegistry, storage: HelixStorage) {
        val descriptor = ToolDescriptor(
            name = ToolName(NAME), version = ToolVersion(1),
            description = "Report the current Goal status after checking your work. You judge whether the objective " +
                "is complete. Include results, checks and remaining limitations in summary. Use in_progress when " +
                "more useful work remains, blocked only when you cannot proceed without external help. " +
                "Report after all other work, then explain the result to the user. This does not grant permissions.",
            inputSchema = schema, outputSchema = schema,
            operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0,
            timeout = 5.seconds, maxOutputBytes = 32768, requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT, executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )
        registry.register(descriptor)
        implementations.register(descriptor, object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val binding = call.turnId?.let { storage.goalTurnBindings.byTurn(it) }
                val run = binding?.let { storage.goalRuns.resolve(it.runId) }
                if (run == null || run.endedAt != null || storage.goals.resolve(run.goalId).state != "RUNNING" ||
                    storage.turns.resolve(requireNotNull(call.turnId)).sessionId != call.sessionId
                ) return ToolExecutorResult.Failed("No active Goal belongs to this request", sideEffectFree = true)
                if (call.args.getValue("summary").jsonPrimitive.content.isBlank()) {
                    return ToolExecutorResult.Failed("Provide a nonblank summary", sideEffectFree = true)
                }
                return ToolExecutorResult.Completed(call.args)
            }
        })
    }
}

internal data class GoalModelReport(val callId: String, val status: String, val summary: String)

/** Only the last tool in this Turn can report completion; later actions invalidate earlier reports. */
internal fun HelixStorage.goalModelReport(turnId: String): GoalModelReport? {
    val call = toolCalls.listByTurn(turnId).lastOrNull() ?: return null
    if (call.name != GoalReportTool.NAME || call.state != "COMPLETED") return null
    val result = toolResults.byToolCall(call.id) ?: return null
    if (result.status != "SUCCESS" || !result.verified) return null
    val args = Json.parseToJsonElement(call.argsJson).jsonObject
    val status = args["status"]?.jsonPrimitive?.content ?: return null
    val summary = args["summary"]?.jsonPrimitive?.content ?: return null
    if (status !in setOf("complete", "in_progress", "blocked") || summary.isBlank() || summary.length > 4096) return null
    return GoalModelReport(call.id, status, summary)
}
''')
p=Path('app/src/main/kotlin/com/helix/app/AppContainer.kt');s=p.read_text().replace('TimeNowTool.register(toolRegistry, toolImplementations, appClock)', 'TimeNowTool.register(toolRegistry, toolImplementations, appClock)\n        com.helix.app.goal.GoalReportTool.register(toolRegistry, toolImplementations, storage)');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt');s=p.read_text().replace('.visible(sessionId, admitted)','.visible(sessionId, admitted)\n            .filter { it.name.value != "goal.report" || control.mode == com.helix.core.model.AgentMode.GOAL }\n            .sortedBy { if (it.name.value == "goal.report") 0 else 1 }');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/GoalRunSettlement.kt');s=p.read_text().replace('import com.helix.app.goal.toRuntimeGoal','import com.helix.app.goal.goalModelReport\nimport com.helix.app.goal.toRuntimeGoal')
s=s.replace('        verifyGoal: ((com.helix.core.agent.Goal, String) -> com.helix.core.agent.Goal)? = null,\n','').replace('settleBoundTurn(turnId, binding.runId, verifyGoal)','settleBoundTurn(turnId, binding.runId)').replace('        verifyGoal: ((com.helix.core.agent.Goal, String) -> com.helix.core.agent.Goal)?,\n','').replace('var goal =','val goal =')
a=s.index('        val verified =');b=s.index('        val next =',a)
s=s[:a]+'''        val report = if (state == TurnState.COMPLETED && !uncertain && !paused) storage.goalModelReport(turnId) else null
        val (event, outcome) =
            if (report?.status == "complete") {
                GoalEvent.CompleteRequested to "MODEL_COMPLETED"
            } else if (report?.status == "blocked") {
                GoalEvent.Blocked to "BLOCKED(MODEL_REPORTED)"
            } else if (!uncertain && paused) {
                GoalEvent.RunFinished to "USER_PAUSED"
            } else {
                decision(state, turn.errorCode, uncertain)
            }
'''+s[b:]
s=s.replace('        goal: com.helix.core.agent.Goal,\n','')
s=s.replace('decision(state, turn.errorCode, uncertain)','decision(goal.correlationId, state, turn.errorCode, uncertain)').replace('    private fun decision(\n','    private fun decision(\n        correlationId: com.helix.core.model.CorrelationId,\n').replace('emptyMap(), goal.correlationId','emptyMap(), correlationId')
a=s.index('            state == TurnState.COMPLETED -> {');b=s.index('\n            errorCode ==',a)
s=s[:a]+'''            state == TurnState.COMPLETED -> GoalEvent.RunFinished to "RUN_FINISHED"
'''+s[b:]
s=s.replace('        storage.goals.updateGoal(next.state.toStoredGoal())','''        storage.goals.updateGoal(next.state.toStoredGoal())
        if (report != null) {
            storage.auditEvents.append(
                idGenerator(), goal.correlationId.value, "goal.model_report", "MODEL",
                """{"status":"${report.status}","toolCallId":"${report.callId}"}""",
                clock.now().toEpochMilli(),
            )
        }''')
p.write_text(s)
p=Path('core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt');s=p.read_text();a=s.index('        val completable =',s.index('private fun onCompleteRequested'));b=s.index('        if (!completable)',a);s=s[:a]+'        val completable = state.state == GoalState.RUNNING\n'+s[b:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/TurnCoordinator.kt');s=p.read_text().replace('        verifyGoal: ((com.helix.core.agent.Goal, String) -> com.helix.core.agent.Goal)? = null,\n','').replace('.settle(turnId, verifyGoal)', '.settle(turnId)');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/ChatService.kt');s=p.read_text();a=s.index('        val verification =',s.index('    private fun terminalize('));b=s.index('        // HXA-036:',a);s=s[:a]+'        coordinator.terminalize(outcome)\n'+s[b:]
a=s.index('    internal suspend fun goalCriteria(');b=s.index('    internal suspend fun setGoalReminder(',a);s=s[:a]+s[b:]
s=s.replace('if (!completeGoalFromEvidence(goalId)) sendNow(text, goalId)','sendNow(text, goalId)')
a=s.index('    internal suspend fun completeGoalFromEvidence(');b=s.index('    private var pendingGoalId',a);s=s[:a]+s[b:]
s=s.replace('    private val goalEvidenceWorkspace: java.io.File? = null,\n','').replace('    private val goalEvidenceFileStore: com.helix.core.workspace.WorkspaceArtifactStore? = null,\n','');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/AppContainer.kt');s=p.read_text().replace('            goalEvidenceWorkspace = appScopeRoot.toFile(),\n','').replace('            goalEvidenceFileStore = workspaceStore,\n','');p.write_text(s)
