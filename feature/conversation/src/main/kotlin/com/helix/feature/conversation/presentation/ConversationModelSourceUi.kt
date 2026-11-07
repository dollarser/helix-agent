package com.helix.feature.conversation.presentation

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ReasoningEffort

/** Explicit host-owned text, resolved by the renderer in its current locale. */
data class ConversationModelLabel(
    val resourceId: Int,
    val arguments: List<String> = emptyList(),
)

enum class ConversationModelSetupStep { ACCOUNT, CONNECTION, MODELS, READY }

enum class ConversationModelCatalogState { FAILED, MISSING_PURPOSES, EMPTY }

data class ConversationModelFailureUi(
    val code: ModelErrorCode?,
    val label: ConversationModelLabel,
)

data class ConversationModelCatalogFeedbackUi(
    val state: ConversationModelCatalogState,
    val failure: ModelErrorCode?,
    val label: ConversationModelLabel,
)

/** Exact-model presentation facts; these never authorize selecting or executing a request. */
data class ConversationModelChoiceUi(
    val id: String,
    val label: String,
    val visible: Boolean,
    val conversationEligible: Boolean,
    val selectable: Boolean,
    val reasoningEfforts: List<ReasoningEffort>,
    val generationFailure: ConversationModelFailureUi?,
    val reasoningNotice: ConversationModelLabel?,
)

/** A picker snapshot, excluding provider configuration, credentials, transport and account records. */
data class ConversationModelSourceUi(
    val id: String,
    val displayName: String,
    val displayLabel: ConversationModelLabel?,
    val provisioning: ProviderProvisioningKind,
    val visibleInModelPicker: Boolean,
    val chatSelectable: Boolean,
    val setupStep: ConversationModelSetupStep,
    val setupLabel: ConversationModelLabel?,
    val knownModels: List<String>,
    val choices: List<ConversationModelChoiceUi>,
    val unavailable: ConversationModelFailureUi?,
    val catalogFeedback: ConversationModelCatalogFeedbackUi?,
) {
    val bundled: Boolean get() = provisioning == ProviderProvisioningKind.BUNDLED_SERVICE

    val conversationModels: List<String> get() = choices.filter { it.visible }.map { it.id }

    fun choice(modelId: String): ConversationModelChoiceUi? = choices.firstOrNull { it.id == modelId }

    fun modelLabel(modelId: String): String = choice(modelId)?.label ?: modelId

    fun modelSelectable(modelId: String): Boolean = choice(modelId)?.selectable == true

    fun conversationEligible(modelId: String): Boolean = choice(modelId)?.conversationEligible == true

    fun reasoningOptionsFor(modelId: String): List<ReasoningEffort> = choice(modelId)?.reasoningEfforts.orEmpty()
}
