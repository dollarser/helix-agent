package com.helix.feature.conversation.presentation

import com.helix.provider.api.SubscriptionRecoveryStatus

/** Display facts only: discovery reads local evidence and never binds a Runtime. */
data class SubscriptionRecoveryUi(
    val turnId: String,
    val modelCallId: String,
    val busy: Boolean = false,
    val status: SubscriptionRecoveryStatus? = null,
    val output: com.helix.provider.api.SubscriptionRecoveredOutput? = null,
    val outputUnavailable: Boolean = false,
    val localResultAvailable: Boolean = false,
)
