package com.helix.feature.conversation.application

import com.helix.core.model.input.InputSkillActivation

/** Conversation admission and lifecycle facts; dispatch remains owned by the App. */
interface ConversationTools {
    fun referenceToolsAvailable(
        sessionId: String,
        conversation: Boolean,
        knowledge: Boolean,
    ): Boolean

    fun validSkillActivations(
        sessionId: String,
        activations: List<InputSkillActivation>,
    ): Boolean

    fun endTurn(turnId: String)

    fun finishTurn(turnId: String)

    fun reviewNativeApproval(
        sessionId: String,
        turnId: String,
        approvalId: String,
        approve: Boolean,
    )

    fun approveApproval(approvalId: String)

    fun denyApproval(approvalId: String)
}
