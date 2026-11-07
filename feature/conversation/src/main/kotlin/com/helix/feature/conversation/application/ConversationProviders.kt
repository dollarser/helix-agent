package com.helix.feature.conversation.application

import com.helix.core.model.ReasoningEffort
import com.helix.feature.conversation.presentation.ConversationModelSourceUi
import com.helix.feature.conversation.presentation.ProviderBindingIssue
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.flow.Flow

/** Current provider facts for a conversation. This port neither edits setup nor grants capabilities. */
interface ConversationProviders {
    val changes: Flow<Unit>

    fun source(
        providerId: String?,
        boundModelId: String?,
    ): ConversationModelSourceUi?

    fun chatSelectable(providerId: String): Boolean

    fun reasoningOptions(
        providerId: String,
        model: String,
    ): List<ReasoningEffort>

    fun bindingIssue(
        providerId: String?,
        modelId: String?,
    ): ProviderBindingIssue?

    suspend fun validateBinding(
        providerId: String?,
        modelId: String?,
    ): ProviderBindingIssue?

    suspend fun capabilitiesFor(
        providerId: String,
        modelId: String?,
    ): ProviderCapabilities?

    suspend fun egressTargetFor(providerId: String): EgressDisclosure.EgressTarget

    suspend fun defaultModel(providerId: String): String

    suspend fun snapshot(
        providerId: String,
        modelId: String?,
    ): String
}
