package com.helix.core.agent

/**
 * Agent interaction mode (architecture doc section 5.1; modes doc section 6.1).
 *
 * - [CHAT]: Q&A; no tools by default.
 * - [PLAN]: read-only research that produces a versioned PlanArtifact.
 * - [ACT]: executes within the current Turn per Policy.
 * - [GOAL]: persistent, budget- and checkpoint-constrained objective; same approval rules as Act.
 */
enum class AgentMode {
    CHAT,
    PLAN,
    ACT,
    GOAL,
}
