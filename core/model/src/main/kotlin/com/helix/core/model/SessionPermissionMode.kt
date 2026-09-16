package com.helix.core.model

/**
 * The session authorization mode chosen by the user (ADR-PERMISSIONS-001 section 1).
 *
 * Only a user UI action can select a mode (ADR section 4); the model, web pages, MCP, Skill and
 * scripts never modify it. The mode is stored per session and compiles — together with the
 * CUSTOM rule snapshot — into ONE [com.helix.core.policy.SessionPermissionConfig] consumed by
 * the single permission resolver: there is no separate execution chain per mode (ADR section 2
 * step 4).
 *
 * - [FULL_ACCESS]: every operation the app can actually perform, including networking,
 *   outside-workspace file operations, commands and device operations. Risk levels still
 *   inform display, audit and anomaly analysis, but never re-gate an authorized operation.
 * - [WORKSPACE]: file operations inside the bound workspace, networking (including the remote
 *   business writes a user task needs), and operations whose local side effects provably stay
 *   inside the workspace; outside-workspace file access, device/system operations and
 *   commands with undetermined side effects ask.
 * - [READ_ONLY]: local read-only operations within the effective read scope, plus network
 *   queries, browsing and reads; local file mutation, remote business writes, commands and
 *   device modifications all ask. Read-only describes the operation effect, not the HTTP
 *   method: a remote post or delete is still a business write.
 * - [CUSTOM]: the user's explicit per-operation-category ALLOW/ASK/DENY rules, copied from a
 *   preset and then edited. The copy is a FIXED snapshot — it never inherits later preset
 *   changes.
 *
 * The explicit `rm -rf` command rule (ADR section 3) requires at least a precise one-time
 * approval in EVERY mode, including [FULL_ACCESS]; the rule feeds the resolver and is not a
 * sixth mode.
 */
enum class SessionPermissionMode {
    FULL_ACCESS,
    WORKSPACE,
    READ_ONLY,
    CUSTOM,
}
