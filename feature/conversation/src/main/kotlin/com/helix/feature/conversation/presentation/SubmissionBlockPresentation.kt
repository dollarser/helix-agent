package com.helix.feature.conversation.presentation

import com.helix.feature.conversation.presentation.ChatScreenState

/** Clears only the pre-admission banner; newer refusals and another session remain authoritative. */
fun clearAcceptedSubmissionBlock(
    screen: ChatScreenState,
    sessionId: String,
    epoch: Long,
): ChatScreenState =
    if (screen.openSessionId == sessionId && screen.blockedReasonEpoch == epoch && screen.modelBindingIssue == null) {
        screen.copy(blockedReason = null, blockedReasonEpoch = epoch + 1)
    } else {
        screen
    }
