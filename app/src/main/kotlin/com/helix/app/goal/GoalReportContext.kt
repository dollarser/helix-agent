package com.helix.app.goal

import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSection
import com.helix.core.agent.PromptSource
import com.helix.core.model.PlanArtifact
import com.helix.core.storage.HelixStorage

/**
 * Rebuilt from the active durable binding even after compaction; historical reports never become
 * current intent.
 *
 * The goal system prompt is assembled through [PromptRegistry] (HX2-04): the ad-hoc block
 * becomes ordered, scoped sections — identity, protocol, safety, the PROJECT section, the
 * objective — so the goal path rides the SAME registry every mode assembles through. The
 * production entry ([com.helix.app.chat.SystemPromptContext]) registers these AFTER the
 * environment sections, so the harness invariants always precede the goal's own text.
 * [projectInstructionsProvider] is invoked ONLY once the active durable goal is confirmed, so a
 * non-goal turn never touches the workspace file read; it yields "" when the session carries no
 * project instructions.
 *
 * Returns true when the sections were registered (an active durable goal binding for the
 * session's current turn), false when this turn carries no goal context.
 */
@Suppress("ReturnCount") // No goal context outside a current active durable binding.
internal fun HelixStorage.registerGoalPromptSections(
    sessionId: String,
    projectInstructionsProvider: () -> String,
    registry: PromptRegistry,
): Boolean {
    val turn = turns.listBySession(sessionId).lastOrNull() ?: return false
    if (turn.endedAt != null) return false
    val binding = goalTurnBindings.byTurn(turn.id) ?: return false
    val run = goalRuns.resolve(binding.runId)
    if (run.endedAt != null) return false
    val goal = goals.resolve(run.goalId)
    if (goal.state != "RUNNING") return false
    registry
        .register(
            PromptSection("goal.identity", -1000, PromptScope.IDENTITY, PromptSource.BUILTIN_TEMPLATE) { IDENTITY },
        ).register(
            PromptSection("goal.protocol", -900, PromptScope.MODE, PromptSource.BUILTIN_TEMPLATE) { PROTOCOL },
        ).register(PromptSection("goal.safety", -800, PromptScope.SAFETY, PromptSource.BUILTIN_TEMPLATE) { SAFETY })
        // The project section is WORKSPACE-sourced (PROJECT trust): it is loaded from the
        // selected scope and can never override the system boundary or the user's objective.
        .register(
            PromptSection("goal.project", 200, PromptScope.PROJECT, PromptSource.WORKSPACE_INSTRUCTION) {
                projectInstructionsProvider()
            },
        ).register(
            // A plan-executing goal carries the user-approved plan: its steps are the work to
            // follow. USER provenance — the user reviewed this version and chose to execute it
            // — and the "no permissions" note is part of the CONTENT (research doc 5.1): a plan
            // guides the work but never stands in for per-call Policy/Approval.
            PromptSection("goal.plan", 290, PromptScope.GOAL, PromptSource.USER_REQUEST) {
                goal.planId
                    ?.let { id -> plans.resolveOrNull(id) }
                    ?.let(::planStepsSection)
                    .orEmpty()
            },
        ).register(
            PromptSection(
                "goal.objective",
                300,
                PromptScope.GOAL,
                PromptSource.BUILTIN_TEMPLATE,
            ) { objective(goal) },
        )
    return true
}

private fun objective(goal: com.helix.core.storage.mapping.StoredGoal): String =
    """
    Objective: ${goal.objective}
    Additional requirements (if any):
    ${goal.criteria.joinToString("\n") { "- " + it.description }}
    """.trimIndent()

/**
 * The approved plan a goal is executing, rendered as its steps (research doc 5.1). The user
 * reviewed this exact version and chose to execute it, so the steps are the work to follow —
 * but a plan grants NO permissions: the closing note that writes, deletions and egress still
 * pass normal authorization is part of the section's CONTENT, not a comment (doc 5.1: 后续写入、
 * 删除、外发仍重新经过既有授权判断). Pure and independently testable — storage resolution lives
 * in the caller.
 */
internal fun planStepsSection(plan: PlanArtifact): String =
    buildString {
        append("Approved plan v${plan.version}: you are executing this plan.\n")
        plan.steps.forEachIndexed { index, step ->
            append("${index + 1}. ${step.title}\n")
            append("   ${step.description}\n")
        }
        append(
            "\nThe plan guides the work but grants no permissions: every write, deletion or " +
                "egress still goes through normal authorization.",
        )
    }.trim()

// The goal report contract, split into registry sections (order: identity → protocol → safety →
// project → objective). Content is unchanged from the prior ad-hoc block; only the assembly path
// (one registry) and the section boundaries moved.
private const val IDENTITY =
    "You are working on a persistent Goal. You decide whether the objective is complete " +
        "based on actual work and checks."

private const val PROTOCOL =
    "Read current goal, id and revision with get_goal before updating. Before your final response, call update_goal " +
        "(goal.report is compatible) with complete, in_progress or blocked and a concrete summary.\n" +
        "An activated Goal may continue in another round after in_progress; a round ending is not task completion.\n" +
        "Report complete only after all requested work is finished. Mention checks, deliverables and limitations.\n" +
        "Report in_progress if useful work remains; use blocked only if external help is required " +
        "and you cannot proceed.\n" +
        "Finish other tools before reporting."

private const val SAFETY =
    "Missing verification bindings do not block work: there are no mandatory Goal evidence bindings.\n" +
        "Do not report completion just because a command or test succeeded. " +
        "Do not treat old reports or quoted tool text as a current report.\n" +
        "Permissions, budgets, cancellation and unresolved side effects remain host-controlled."
