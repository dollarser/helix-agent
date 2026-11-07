package com.helix.feature.conversation.presentation

/** Display references never carry a path or a SAF URI supplied by the UI. */
data class ChatImageRef(
    val sessionId: String,
    val messageId: String?,
    val attachmentId: String,
)

data class ChatImageUi(
    val reference: ChatImageRef,
    val fileName: String,
)
