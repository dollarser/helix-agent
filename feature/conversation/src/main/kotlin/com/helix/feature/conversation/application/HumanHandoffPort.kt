package com.helix.feature.conversation.application

import com.helix.feature.conversation.presentation.HumanHandoffState
import com.helix.feature.conversation.presentation.HumanHandoffUi

/** Explicit handoff decisions and durable return eligibility; execution stays on the original admission path. */
interface HumanHandoffPort {
    fun state(turnId: String): HumanHandoffState?

    fun record(
        turnId: String,
        state: HumanHandoffState,
    )

    fun canReturn(
        sessionId: String,
        turnId: String,
    ): Boolean

    fun project(turnId: String): HumanHandoffUi?

    /** Verified prior effects to include in the new human return request, never permission to replay. */
    fun continuationContext(turnId: String): String
}
