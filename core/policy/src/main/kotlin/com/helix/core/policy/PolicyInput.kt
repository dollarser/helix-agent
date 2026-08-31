package com.helix.core.policy

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolOperationClass

/**
 * The trusted per-call fact set the Policy Engine composes dynamic risk from (roadmap HXA-033:
 * mode, Safety Profile, scope, data sensitivity, normalized network origin/residence, tool
 * source, execution target and parameters; architecture doc section 8).
 *
 * Every field is produced by the trusted execution path (registry, dispatcher, capability
 * center, user settings) — none of them is a tool argument. In particular [profile],
 * [lanScopes] and [missingCapabilities] can never come from model, MCP or Skill content, and
 * the egress residence is derived from [EgressRequest.endpoint] alone. These API-shape
 * constraints are how "模型、MCP 或 Skill 不能切换 Profile、创建 LAN scope 或降低
 * residence" is enforced (ADR-0005).
 */
data class PolicyInput(
    /** The descriptor's static base risk; dynamic risk can only equal or exceed it. */
    val baseRisk: RiskLevel,
    /** The descriptor's operation effect class (Plan-mode filter stays operation-class primary). */
    val operationClass: ToolOperationClass,
    /** The agent mode of the current turn. */
    val mode: AgentMode,
    /** The runtime safety profile of this installation (user state, never a call parameter). */
    val profile: SafetyProfile,
    /** The registered source of the tool (built-in / MCP / Skill + binding hashes). */
    val source: ToolCallSource,
    /** Where the call executes; PRoot/CLI runtimes are ADVANCED-only (ADR-0005). */
    val executionTarget: ExecutionTargetType,
    /** Where the call's data comes from (drives the egress sensitivity floor). */
    val dataOrigin: DataOrigin,
    /** The user scope active for this call (workspace/tree/tab/session); null when unscoped. */
    val scope: UserScope?,
    /** True when the call overwrites existing data (architecture doc section 8). */
    val overwritesExisting: Boolean = false,
    /** True when the generated code or command changed since the last approved version. */
    val codeOrCommandChanged: Boolean = false,
    /** True when an MCP schema hash, Skill snapshot hash or other binding field changed. */
    val sourceBindingChanged: Boolean = false,
    /** Capabilities the Capability Center resolved as not currently usable for this call. */
    val missingCapabilities: Set<Capability> = emptySet(),
    /** The egress facet when the call sends data off-device; null otherwise. */
    val egress: EgressRequest? = null,
    /** Trusted session fact: has this exact origin already appeared in this session? */
    val originSeenInSession: Boolean = true,
    /**
     * The user-created exact LAN/loopback scopes (ADVANCED settings state; empty under
     * STANDARD — the gate denies LAN/loopback there outright).
     */
    val lanScopes: Set<NetworkOriginScope> = emptySet(),
)
