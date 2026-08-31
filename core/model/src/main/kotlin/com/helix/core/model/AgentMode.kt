package com.helix.core.model

/**
 * Agent interaction mode (architecture doc section 5.1; modes doc section 6.1).
 *
 * - [CHAT]: Q&A; no tools by default.
 *
 * Lives in core:model (moved from core:agent, HXA-033) because the Policy Engine
 * (core:policy) composes dynamic risk from the mode (roadmap HXA-033); core:agent
 * still owns the mode filter (ModePolicy).
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
