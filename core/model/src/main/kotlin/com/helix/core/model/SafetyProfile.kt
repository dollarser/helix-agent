package com.helix.core.model

/**
 * Runtime safety profile (ADR-0005, accepted): [STANDARD] is the default of every
 * installation; [ADVANCED] exists only in the developer variant and must be switched
 * on explicitly after the user reads the risk explanation. The profile is orthogonal
 * to the compile-time consumer/developer boundary and is never a ToolCall parameter
 * (ADR-0005: model, MCP, Skill or imported content cannot switch it).
 *
 * Lives in core:model (moved from app, HXA-033) because the Policy Engine
 * (core:policy) gates high-sensitivity egress and isolated runtimes on the profile.
 */
enum class SafetyProfile { STANDARD, ADVANCED }
