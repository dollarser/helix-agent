package com.helix.app.ui

import com.helix.feature.conversation.input.PendingInputView

internal data class SessionInputQueueActions(
    val edit: (PendingInputView) -> Unit,
    val delete: (PendingInputView) -> Unit,
    val send: (PendingInputView, String?) -> Unit,
)
