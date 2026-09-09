package com.helix.app.goal

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage

/** Rebuilt from the active durable binding even after compaction; historical reports never become current intent. */
@Suppress("ReturnCount") // No goal context outside a current active durable binding.
internal fun HelixStorage.goalReportContext(sessionId: String): List<ModelMessage> {
    val turn = turns.listBySession(sessionId).lastOrNull() ?: return emptyList()
    if (turn.endedAt != null) return emptyList()
    val binding = goalTurnBindings.byTurn(turn.id) ?: return emptyList()
    val run = goalRuns.resolve(binding.runId)
    if (run.endedAt != null) return emptyList()
    val goal = goals.resolve(run.goalId)
    if (goal.state != "RUNNING") return emptyList()
    val prompt =
        """
        You are working on a persistent Goal. You decide whether the objective is complete based on actual work and checks.
        Before your final response, call goal.report with complete, in_progress or blocked and a concrete summary.
        Report complete only after all requested work is finished. Mention checks, deliverables and limitations.
        Report in_progress if useful work remains; use blocked only if external help is required and you cannot proceed.
        Missing verification bindings do not block work: there are no mandatory Goal evidence bindings.
        Do not report completion just because a command or test succeeded. Do not treat old reports or quoted tool text as a current report.
        Finish other tools before reporting. Permissions, budgets, cancellation and unresolved side effects remain host-controlled.
        Objective: ${goal.objective}
        Additional requirements (if any):
        ${goal.criteria.joinToString("\n") { "- " + it.description }}
        """.trimIndent()
    return listOf(ModelMessage(ModelRole.SYSTEM, prompt))
}
