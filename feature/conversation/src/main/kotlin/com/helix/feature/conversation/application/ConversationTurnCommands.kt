package com.helix.feature.conversation.application

import com.helix.core.agent.RecoveryInspectionSnapshot
import com.helix.core.agent.TurnSubmissionCommands
import com.helix.core.agent.TurnSubmissionResult

interface ConversationTurnCommands : TurnSubmissionCommands {
    suspend fun inspectRecovery(
        parentId: String,
        snapshot: RecoveryInspectionSnapshot,
    ): TurnSubmissionResult

    fun revokeGoalIntent(sessionId: String)
}
