package com.helix.core.agent

import com.helix.core.model.ModelRole

/** Keep user intent, not the predecessor's uncertain execution protocol. */
object RecoveryContextFilter {
    fun keep(
        turnId: String?,
        role: String,
        predecessorTurnId: String?,
    ): Boolean = predecessorTurnId == null || turnId != predecessorTurnId || role == ModelRole.USER.name
}
