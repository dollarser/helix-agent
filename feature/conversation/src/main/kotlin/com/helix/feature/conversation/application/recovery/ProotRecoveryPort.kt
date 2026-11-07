package com.helix.feature.conversation.application.recovery

import com.helix.feature.conversation.presentation.ProotRecoveredOutput
import com.helix.feature.conversation.presentation.ProotRecoveryReport

/** Original-job operations stay separate: querying never grants stop, collection, or replay authority. */
interface ProotRecoveryPort {
    val available: Boolean

    fun inspect(
        turnId: String,
        callId: String,
        stop: Boolean,
    ): ProotRecoveryReport

    fun collect(
        turnId: String,
        callId: String,
        localOnly: Boolean,
    ): ProotRecoveredOutput?
}
