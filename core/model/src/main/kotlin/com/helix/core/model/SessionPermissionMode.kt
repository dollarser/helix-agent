package com.helix.core.model

/**
 * The session authorization mode chosen by the user (ADR-PERMISSIONS-001 section 1).
 *
 * Only a user UI action can select a mode. The model, web pages, MCP, Skill and scripts never
 * modify it. Every preset compiles into ONE [com.helix.core.policy.SessionPermissionConfig]
 * consumed by the same resolver; [CUSTOM] is the user's explicit ALLOW/ASK/DENY snapshot.
 *
 * - [APPROVAL_REQUIRED]: the default. Workspace reads proceed; external reads and every mutation,
 *   command, remote business write or device/system mutation ask.
 * - [WORKSPACE_TRUSTED]: workspace reads and mutations proceed; external file effects, commands,
 *   remote business writes and device/system mutations ask.
 * - [FULL_ACCESS]: every ordinary effect the app can actually perform is allowed by the session
 *   config. Global capability/integrity invariants and the explicit destructive-command rule
 *   remain in force.
 * - [READ_ONLY]: workspace reads proceed and external reads ask; mutations, commands, remote
 *   business writes and device/system mutations are DENIED, never downgraded to one-time approval.
 * - [CUSTOM]: the user's explicit per-operation-category ALLOW/ASK/DENY rules copied from a preset
 *   and then edited.
 */
enum class SessionPermissionMode {
    APPROVAL_REQUIRED,
    WORKSPACE_TRUSTED,
    FULL_ACCESS,
    READ_ONLY,
    CUSTOM,
}
