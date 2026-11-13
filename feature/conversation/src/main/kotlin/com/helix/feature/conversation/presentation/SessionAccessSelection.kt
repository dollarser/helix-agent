package com.helix.feature.conversation.presentation

data class SessionAccessSelection(
    val targetSessionId: String,
    val readHistory: Boolean,
    val manageMetadata: Boolean,
    val delegateTasks: Boolean = false,
)
