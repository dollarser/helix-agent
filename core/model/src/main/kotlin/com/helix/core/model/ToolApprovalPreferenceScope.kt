package com.helix.core.model

/**
 * The scope a [ToolApprovalPreference] applies to (ADR-0052 point 5). A preference is always for
 * one tool identity (stable source + name); [scopeKind] says how narrowly it is bound:
 *
 * - [GLOBAL] is the per-tool base preference (no session/workspace binding).
 * - [WORKSPACE] is a narrower restriction for one workspace (the session's `directoryRef`).
 * - [SESSION] is the narrowest, per-conversation restriction.
 *
 * Specificity rises GLOBAL < WORKSPACE < SESSION. A DENY at any applicable scope is authoritative
 * (a narrower ALLOW cannot override an outer DENY); otherwise the narrowest present scope wins.
 * The resolution rule lives in core:policy ([com.helix.core.policy.ToolApprovalResolver]), keeping
 * this enum a plain value.
 */
enum class ToolApprovalPreferenceScope {
    /** Per-tool, applies to every session and workspace. */
    GLOBAL,

    /** Per-tool within one workspace (directory). */
    WORKSPACE,

    /** Per-tool within one session (most specific). */
    SESSION,
}
