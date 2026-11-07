package com.helix.app.goal

import com.helix.app.application.context.GoalPromptFacts
import com.helix.app.application.context.PromptTemplateSource
import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSection
import com.helix.core.agent.PromptSource
import com.helix.core.model.PlanArtifact

/** Pure mapping of a confirmed, request-bound durable goal and its authorized workspace text. */
internal fun registerGoalPromptSections(
    goal: GoalPromptFacts,
    projectInstructions: String,
    registry: PromptRegistry,
    templates: PromptTemplateSource,
) {
    registry
        .register(
            PromptSection("goal.identity", -1000, PromptScope.IDENTITY, PromptSource.BUILTIN_TEMPLATE) {
                templates.text("goal-identity").trim()
            },
        ).register(
            PromptSection("goal.protocol", -900, PromptScope.MODE, PromptSource.BUILTIN_TEMPLATE) {
                templates.text("goal-protocol").trim()
            },
        ).register(
            PromptSection("goal.safety", -800, PromptScope.SAFETY, PromptSource.BUILTIN_TEMPLATE) {
                templates.text("goal-safety").trim()
            },
        )
        // The project section is WORKSPACE-sourced (PROJECT trust): it is loaded from the
        // selected scope and can never override the system boundary or the user's objective.
        .register(
            PromptSection("goal.project", 200, PromptScope.PROJECT, PromptSource.WORKSPACE_INSTRUCTION) {
                projectInstructions
            },
        ).register(
            // A plan-executing goal carries the user-approved plan: its steps are the work to
            // follow. USER provenance — the user reviewed this version and chose to execute it
            // — and the "no permissions" note is part of the CONTENT (research doc 5.1): a plan
            // guides the work but never stands in for per-call Policy/Approval.
            PromptSection("goal.plan", 290, PromptScope.GOAL, PromptSource.USER_REQUEST) {
                goal.plan
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
}

private fun objective(goal: GoalPromptFacts): String =
    """
    Objective: ${goal.objective}
    Additional requirements (if any):
    ${goal.criteria.joinToString("\n") { "- " + it }}
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
