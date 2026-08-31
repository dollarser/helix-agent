package com.helix.core.policy

import com.helix.core.model.Capability
import java.time.Instant

/**
 * The result of one real system-state check for one capability (platform capabilities doc
 * section 2).
 *
 * A [CapabilityGrant] can only be produced by the platform adapter layer ([CapabilityResolver])
 * from the real system state at check time. The model cannot construct, modify or cache one in
 * any meaningful way: the execution path always re-resolves (doc 9 section 2: "Tool 执行时必须
 * 再次检查；不能因为数据库里曾记录 GRANTED 就跳过系统状态检查"). A value rehydrated from the
 * audit store must carry [grantedBySystem] = false and is then never [isUsable].
 */
data class CapabilityGrant(
    val capability: Capability,
    val state: GrantState,
    val grantedBySystem: Boolean,
    val userScope: UserScope?,
    val checkedAt: Instant,
) {
    /**
     * The only grant shape usable at execution time: freshly resolved (system-verified) and
     * currently granted. Denied, unavailable, lost or audit-rehydrated grants all fail closed.
     */
    val isUsable: Boolean
        get() = state == GrantState.GRANTED && grantedBySystem
}
