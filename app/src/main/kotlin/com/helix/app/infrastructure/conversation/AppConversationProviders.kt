package com.helix.app.infrastructure.conversation

import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTurnSnapshot
import com.helix.core.model.ReasoningEffort
import com.helix.feature.conversation.application.ConversationProviders
import kotlinx.coroutines.flow.combine

/** Reads the current provider authority; returned UI snapshots do not bypass subsequent admission. */
internal class AppConversationProviders(
    private val providers: ProviderService,
) : ConversationProviders {
    override val changes = combine(providers.contextRevision, providers.rows) { _, _ -> Unit }

    override fun source(
        providerId: String?,
        boundModelId: String?,
    ) = providers.rows.value
        .firstOrNull { it.id == providerId }
        ?.conversationModelSource(boundModelId)

    override fun chatSelectable(providerId: String) = providers.chatSelectable(providerId)

    override fun reasoningOptions(
        providerId: String,
        model: String,
    ): List<ReasoningEffort> = providers.reasoningOptions(providerId, model)

    override fun bindingIssue(
        providerId: String?,
        modelId: String?,
    ) = providers.bindings.project(providerId, modelId)

    override suspend fun validateBinding(
        providerId: String?,
        modelId: String?,
    ) = providers.bindings.validate(providerId, modelId)

    override suspend fun capabilitiesFor(
        providerId: String,
        modelId: String?,
    ) = providers.capabilitiesFor(providerId, modelId)

    override suspend fun egressTargetFor(providerId: String) = providers.egressTargetFor(providerId)

    override suspend fun defaultModel(providerId: String) = providers.storedConfig(providerId).model

    override suspend fun snapshot(
        providerId: String,
        modelId: String?,
    ) = ProviderTurnSnapshot.capture(providers, providerId, modelId)
}
