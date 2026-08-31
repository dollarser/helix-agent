package com.helix.app.capability

import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.CapabilityGrantRecorder
import com.helix.core.storage.HelixStorage

/**
 * The app's [CapabilityGrantRecorder] (HXA-032): writes each fresh check through to the
 * `capability_grants` audit rows (architecture doc 9.1: 权限状态缓存，不代替实时检查).
 *
 * Write-only by contract — nothing in the execution path ever reads these rows back as a grant.
 * Fail-closed: a storage failure propagates and blocks the capability check (AGENTS.md: no
 * catch-all success).
 */
class StorageCapabilityGrantRecorder(
    private val storage: HelixStorage,
) : CapabilityGrantRecorder {
    override fun record(grant: CapabilityGrant) {
        storage.capabilityGrants.record(
            type = grant.capability.name,
            systemState = grant.state.name,
            userScopeRef = grant.userScope?.toScopeRef() ?: NO_SCOPE_REF,
            checkedAt = grant.checkedAt.toEpochMilli(),
        )
    }

    private companion object {
        /** Sentinel for a grant without a user scope; the column is non-blank by schema (HXA-014). */
        const val NO_SCOPE_REF = "none"
    }
}
