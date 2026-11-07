package com.helix.feature.conversation.presentation

/**
 * One row of the conversation's Progress section (HX2-07, research doc section 16).
 * Public: it is projected into the public [ChatScreenState.taskLedger].
 */
data class LedgerItemUi(
    val id: String,
    val title: String,
    val state: String,
)
