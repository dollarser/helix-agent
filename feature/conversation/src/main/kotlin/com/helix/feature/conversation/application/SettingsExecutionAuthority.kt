package com.helix.feature.conversation.application

import com.helix.core.model.CancelSignal
import java.time.Instant

/** Original dispatcher identity and live cancellation, never an authorization grant by itself.
 * The durable catalog must still validate the running turn and tool row before committing.
 */
data class SettingsExecutionAuthority(
    val sessionId: String,
    val turnId: String?,
    val toolCallId: String,
    val toolName: String,
    val deadline: Instant,
    val cancel: CancelSignal,
) {
    fun isStopped(now: Instant): Boolean = cancel.isCancelled() || !now.isBefore(deadline)
}
