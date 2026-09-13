package com.helix.app.goal

import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSection
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage

/**
 * Rebuilt from the active durable binding even after compaction; historical reports never become
 * current intent.
 *
 * The goal system prompt is assembled through [PromptRegistry] (HX2-04): the ad-hoc block becomes
 * ordered, scoped sections — identity, protocol, safety, the PROJECT section, the objective — so
 * the goal path rides the SAME registry every mode assembles through. [projectInstructionsProvider]
 * is invoked ONLY once the active durable goal is confirmed, so a non-goal turn never touches the
 * workspace file read; it yields "" when the session carries no project instructions.
 */
@Suppress("ReturnCount") // No goal context outside a current active durable binding.
internal fun HelixStorage.goalReportContext(
    sessionId: String,
    projectInstructionsProvider: () -> String,
): List<ModelMessage> {
    val turn = turns.listBySession(sessionId).lastOrNull() ?: return emptyList()
    if (turn.endedAt != null) return emptyList()
    val binding = goalTurnBindings.byTurn(turn.id) ?: return emptyList()
    val run = goalRuns.resolve(binding.runId)
    if (run.endedAt != null) return emptyList()
    val goal = goals.resolve(run.goalId)
    if (goal.state != "RUNNING") return emptyList()
    val prompt =
        PromptRegistry()
            .register(PromptSection("goal.identity", -1000, PromptScope.IDENTITY) { IDENTITY })
            .register(PromptSection("goal.protocol", -900, PromptScope.MODE) { PROTOCOL })
            .register(PromptSection("goal.safety", -800, PromptScope.SAFETY) { SAFETY })
            .register(PromptSection("goal.project", 200, PromptScope.PROJECT) { projectInstructionsProvider() })
            .register(PromptSection("goal.objective", 300, PromptScope.GOAL) { objective(goal) })
            .assemble()
    return listOf(ModelMessage(ModelRole.SYSTEM, prompt))
}

private fun objective(goal: com.helix.core.storage.mapping.StoredGoal): String =
    """
    Objective: ${goal.objective}
    Additional requirements (if any):
    ${goal.criteria.joinToString("\n") { "- " + it.description }}
    """.trimIndent()

// The goal report contract, split into registry sections (order: identity → protocol → safety →
// project → objective). Content is unchanged from the prior ad-hoc block; only the assembly path
// (one registry) and the section boundaries moved.
private const val IDENTITY =
    "You are working on a persistent Goal. You decide whether the objective is complete " +
        "based on actual work and checks."

private const val PROTOCOL =
    "Before your final response, call goal.report with complete, in_progress or blocked and a concrete summary.\n" +
        "Report complete only after all requested work is finished. Mention checks, deliverables and limitations.\n" +
        "Report in_progress if useful work remains; use blocked only if external help is required " +
        "and you cannot proceed.\n" +
        "Finish other tools before reporting."

private const val SAFETY =
    "Missing verification bindings do not block work: there are no mandatory Goal evidence bindings.\n" +
        "Do not report completion just because a command or test succeeded. " +
        "Do not treat old reports or quoted tool text as a current report.\n" +
        "Permissions, budgets, cancellation and unresolved side effects remain host-controlled."
