package com.helix.core.model

/**
 * Static risk level declared by a tool (`baseRisk`) and dynamic risk computed by the Policy
 * Engine per call (architecture doc section 8).
 *
 * Levels are ordinal-ordered L0 < L1 < L2 < L3. `L2` and `L3` always require per-call user
 * approval; unknown tools, unknown capabilities and `L3` are denied by default (roadmap
 * HXA-033, Policy Engine). [ToolOperationClass] is orthogonal to risk: Plan mode filters on
 * `READ_ONLY` only and must never substitute a `baseRisk <= L1` check.
 */
enum class RiskLevel {
    L0,
    L1,
    L2,
    L3,
    ;

    val requiresApproval: Boolean
        get() = this == L2 || this == L3

    fun atLeast(other: RiskLevel): Boolean = this.ordinal >= other.ordinal

    fun min(other: RiskLevel): RiskLevel = if (this <= other) this else other

    companion object {
        val DEFAULT_DENY_LEVEL: RiskLevel = L3
    }
}

/**
 * Unified platform capability enum, shared by ToolDescriptor, the Capability Center and the
 * platform resolvers (platform capabilities doc section 2). System permission states only
 * describe what the app can do; they never replace per-call Tool Policy.
 */
enum class Capability {
    WEB_BROWSING,
    SAF_DOCUMENT_TREE,
    MANAGE_ALL_FILES,
    ACCESSIBILITY_AUTOMATION,
    ROOT_SHELL,
    NOTIFICATION_READ,
    CALENDAR_WRITE,
}

/**
 * Operation effect class of a tool (architecture doc section 7). It describes what the tool
 * does, independent of dynamic risk. MCP annotations can never reclassify a tool as [READ_ONLY].
 */
enum class ToolOperationClass {
    READ_ONLY,
    LOCAL_MUTATION,
    NETWORK,
    EXTERNAL_ACTION,
    CODE_EXECUTION,
    PRIVILEGED,
}
