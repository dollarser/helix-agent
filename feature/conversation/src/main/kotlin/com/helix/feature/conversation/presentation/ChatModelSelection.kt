package com.helix.feature.conversation.presentation

/** A provider's configured default is not a model selected for this conversation. */
fun hasSelectedConversationModel(
    providerId: String?,
    modelId: String?,
): Boolean = !providerId.isNullOrBlank() && !modelId.isNullOrBlank()
