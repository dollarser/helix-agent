package com.helix.app.chat

/** A provider's configured default is not a model selected for this conversation. */
internal fun hasSelectedConversationModel(
    providerId: String?,
    modelId: String?,
): Boolean = !providerId.isNullOrBlank() && !modelId.isNullOrBlank()
