package com.helix.core.policy

import com.helix.core.model.Capability

/**
 * The system real-state resolver contract (platform capabilities doc section 2: "CapabilityGrant
 * 只能由平台适配层根据系统真实状态产生").
 *
 * Implementations query the actual platform state on EVERY call (runtime permission state,
 * external-storage-manager state, enabled accessibility services, WebView presence, root
 * integration) and never answer from a cache. They must stamp [CapabilityGrant.checkedAt] with
 * the check time, keep [CapabilityGrant.grantedBySystem] = true, and stay deterministic for an
 * unchanged system state (doc 9 section 8: stable errors under no-permission, denied and
 * revoked conditions).
 */
interface CapabilityResolver {
    fun resolve(capability: Capability): CapabilityGrant
}
