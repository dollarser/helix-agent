from pathlib import Path
base=Path('app/src/main/kotlin/com/helix/app')
def edit(path, old, new):
 p=base/path;s=p.read_text();assert old in s,(path,old[:80]);p.write_text(s.replace(old,new))
edit(Path('runcontrol/RunControlStore.kt'),'import com.helix.core.model.AgentMode','import com.helix.core.model.GoalBudgets\nimport com.helix.core.model.AgentMode')
edit(Path('runcontrol/RunControlStore.kt'),'    val reasoning: ReasoningEffort = ReasoningEffort.OFF,','    val reasoning: ReasoningEffort = ReasoningEffort.OFF,\n    val goalBudgets: GoalBudgets = GoalBudgetDefaults.VALUE,')
edit(Path('runcontrol/RunControlStore.kt'),'    fun setBudgets(budgets: TurnBudgets)\n','    fun setBudgets(budgets: TurnBudgets)\n\n    fun setGoalBudgets(budgets: GoalBudgets)\n')
edit(Path('runcontrol/RunControlStore.kt'),'    private fun update(next: RunControlConfig) {','    override fun setGoalBudgets(budgets: GoalBudgets) {\n        store.setLines("goal_defaults_v1", listOf(budgets.toStorageString()))\n        state.value = state.value.copy(goalBudgets = budgets)\n    }\n\n    private fun update(next: RunControlConfig) {')
edit(Path('runcontrol/RunControlStore.kt'),'lines.getOrNull(3)?.let(ReasoningEffort::valueOf) ?: ReasoningEffort.OFF,','lines.getOrNull(3)?.let(ReasoningEffort::valueOf) ?: ReasoningEffort.OFF,\n                readGoalBudgets(),')
edit(Path('runcontrol/RunControlStore.kt'),'RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT)\n        }','RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT, goalBudgets = readGoalBudgets())\n        }\n\n    @Suppress("SwallowedException") // Only malformed preferences fall back; saved Goals are never changed.\n    private fun readGoalBudgets(): GoalBudgets =\n        try {\n            store.lines("goal_defaults_v1").singleOrNull()?.let(GoalBudgets::parse) ?: GoalBudgetDefaults.VALUE\n        } catch (_: IllegalArgumentException) {\n            GoalBudgetDefaults.VALUE\n        }')
(base/'runcontrol/GoalBudgetDefaults.kt').write_text('''package com.helix.app.runcontrol

import com.helix.core.model.GoalBudgets

/** New-Goal defaults only. Per-request model/window limits remain resolved from live metadata. */
object GoalBudgetDefaults {
    val VALUE = GoalBudgets(128, 256, 4_000_000, 7_200_000, 1_800_000, 0)
}
''')
# Sending a prompt uses the normal admission, draft, attachment and disclosure pipeline.
p=base/'ui/ChatScreen.kt';s=p.read_text();start=s.index('                            if (runControl.mode == AgentMode.GOAL)');end=s.index('\n                        },',start);s=s[:start]+'''                            chatService.send(input.trim())
                            input = ""'''+s[end:];s=s.replace('import com.helix.core.model.AgentMode\n','').replace('import androidx.compose.runtime.rememberCoroutineScope\n','').replace('import kotlinx.coroutines.launch\n','').replace('    val uiScope = rememberCoroutineScope()\n','');s=s.replace('            busy = screen.isSending,','            busy = screen.isSending,\n            onSettings = onProviders,');p.write_text(s)
edit(Path('ui/ConversationComposer.kt'),'enabled = isSending || goalMode || input.isNotBlank() || hasAttachments,','enabled = isSending || input.isNotBlank() || (!goalMode && hasAttachments),')
edit(Path('ui/ConversationComposer.kt'),'goalMode -> R.string.chat_open_goals','goalMode -> R.string.common_send')
# Create only inside the existing serialized start gate after all send checks, atomically with first Turn.
edit(Path('chat/ChatService.kt'),'            val goalStart =\n                goalId?.let {','''            val goalStart =
                if (goalId == null && control.mode == AgentMode.GOAL && retryTurnId == null && text != null) {
                    GoalComposerStart(storage, clock, idGenerator).start(text, spec, control)
                } else goalId?.let {''')
edit(Path('chat/ChatService.kt'),'            if (goalId != null && goalStart == null) {','            if ((goalId != null || control.mode == AgentMode.GOAL) && goalStart == null && retryTurnId == null) {')
(base/'chat/GoalComposerStart.kt').write_text('''package com.helix.app.chat

import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.Goal
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage

/** Called under the send gate. Creation and first run commit together; failed admission creates nothing. */
internal class GoalComposerStart(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    fun start(text: String, spec: TurnStartSpec, control: RunControlConfig): StartedGoalTurn? {
        require(text.isNotBlank())
        var started: StartedGoalTurn? = null
        storage.withTransaction {
            val coordinator = GoalRunCoordinator(storage, clock, idGenerator)
            val latestGoal = storage.turns.listBySession(spec.sessionId).asReversed().firstNotNullOfOrNull { turn ->
                storage.goalTurnBindings.byTurn(turn.id)?.let { binding ->
                    storage.goalRuns.listByGoal(binding.goalId)
                    storage.goals.resolve(binding.goalId)
                }
            }
            val existing = latestGoal?.takeUnless { GoalState.valueOf(it.state).isTerminal }
            if (existing != null) {
                started = coordinator.start(GoalTurnStart(existing.id, GoalWakeReason.USER_OPEN, spec, control.budgets))
            } else {
                // Full instructions remain in the first persisted user message; the objective is its compact label.
                val objective = text.trim().take(Goal.MAX_OBJECTIVE_LENGTH)
                val id = coordinator.create(objective, emptyList(), control.goalBudgets)
                started = coordinator.start(GoalTurnStart(id, GoalWakeReason.USER_OPEN, spec, control.budgets))
                check(started != null) { "New Goal could not start" }
            }
        }
        return started
    }
}
''')
