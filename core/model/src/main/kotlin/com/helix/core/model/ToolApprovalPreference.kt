package com.helix.core.model

/**
 * The user's durable three-state preference for a tool (ADR-0052).
 *
 * This is a *preference* — a standing user setting for a named tool — and is deliberately
 * distinct from [ApprovalDecision], which records the one-shot APPROVED/DENIED outcome of a
 * single ToolCall. A preference is only ever written by the user application service; the
 * model, Skill, MCP and A2A agents cannot write one (ADR-0052 point 1).
 *
 * [ALLOW] means "within the already-authorized scope and under the current policy, skip the
 * per-call confirmation". It is *not* a grant: it creates no capability, no file scope and no
 * wildcard L2/L3 approval, and a high-risk call still requires its exact proof (ADR-0052 point 2).
 * [ASK] is an extra user-imposed restriction: it forces a per-call confirmation even where the
 * policy would otherwise resolve a low-risk call automatically. [DENY] blocks the tool at model
 * exposure and at the execution boundary.
 */
enum class ToolApprovalPreference {
    /** Within the authorized scope and current policy, resolve the call without a confirmation card. */
    ALLOW,

    /** Require a per-call confirmation, including for low-risk tools the policy would auto-resolve. */
    ASK,

    /** Block the tool: hidden from model exposure and rejected at the execution boundary. */
    DENY,
}
