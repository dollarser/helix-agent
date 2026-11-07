package com.helix.feature.conversation.presentation

enum class HumanHandoffState { REQUESTED, SUBMITTED, FINISHED, CANCELLED }

data class HumanHandoffUi(
    val turnId: String,
    val state: HumanHandoffState,
    val canReturn: Boolean,
)
