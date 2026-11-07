package com.helix.feature.conversation.presentation

import com.helix.feature.conversation.input.PendingInputView

data class SessionInputQueueActions(
    val edit: (PendingInputView) -> Unit,
    val delete: (PendingInputView) -> Unit,
    val send: (PendingInputView, String?) -> Unit,
)
