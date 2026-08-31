package com.helix.core.policy

import com.helix.core.model.Capability

/**
 * Write-only audit sink for capability checks (architecture doc 9.1 `capability_grants` —
 * "权限状态缓存，不代替实时检查"). The center only ever writes through it and never reads it
 * back, so stored rows can structurally never substitute for an execution-time check.
 *
 * Fail-closed: a recorder failure propagates out of [CapabilityCenter.check] — a capability
 * check that cannot be audited is not a successful one (AGENTS.md: no catch-all success).
 */
interface CapabilityGrantRecorder {
    fun record(grant: CapabilityGrant)
}

/**
 * The Capability Center facade (platform capabilities doc sections 2 and 8.1; architecture doc
 * 7.1 pipeline). Tool execution asks the center, the center asks the live resolver, and the
 * result is recorded for audit — nothing in this path ever reads a previous result.
 *
 * Invariant (缓存不代替执行时检查): every [check] call goes through the live [resolver];
 * previously returned or recorded grants are never served.
 */
class CapabilityCenter(
    private val resolver: CapabilityResolver,
    private val recorder: CapabilityGrantRecorder? = null,
) {
    /** Resolves [capability] against the real system state right now, then records it for audit. */
    fun check(capability: Capability): CapabilityGrant {
        val grant = resolver.resolve(capability)
        recorder?.record(grant)
        return grant
    }

    /**
     * Execution-time check for a tool's `requiredCapabilities` set: every capability is resolved
     * live and [CapabilityEvaluation.missing] lists the ones that are not usable.
     */
    fun evaluate(required: Set<Capability>): CapabilityEvaluation {
        val grants = required.associateWith { check(it) }
        val missing =
            grants.entries
                .filterNot { (_, grant) -> grant.isUsable }
                .map { (capability, _) -> capability }
                .sorted()
        return CapabilityEvaluation(grants, missing)
    }
}

/** The execution-time outcome of checking a set of required capabilities. */
data class CapabilityEvaluation(
    val grants: Map<Capability, CapabilityGrant>,
    val missing: List<Capability>,
) {
    /** True when every required capability is currently usable. */
    val satisfied: Boolean
        get() = missing.isEmpty()
}
