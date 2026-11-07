package com.helix.app.infrastructure.conversation

import com.helix.app.R
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderSetupStep
import com.helix.app.provider.reasoningOptionsFor
import com.helix.feature.conversation.presentation.ConversationModelCatalogFeedbackUi
import com.helix.feature.conversation.presentation.ConversationModelCatalogState
import com.helix.feature.conversation.presentation.ConversationModelChoiceUi
import com.helix.feature.conversation.presentation.ConversationModelFailureUi
import com.helix.feature.conversation.presentation.ConversationModelLabel
import com.helix.feature.conversation.presentation.ConversationModelSetupStep
import com.helix.feature.conversation.presentation.ConversationModelSourceUi
import com.helix.feature.conversation.R as ConversationR
import com.helix.ui.shared.R as SharedR

/** Capture the current binding too: a hidden model is not necessarily in the refreshed catalog. */
fun List<ProviderRowUi>.conversationModelSources(
    boundProviderId: String?,
    boundModelId: String?,
): List<ConversationModelSourceUi> =
    map { row -> row.conversationModelSource(boundModelId.takeIf { row.id == boundProviderId }) }

fun ProviderRowUi.conversationModelSource(boundModelId: String? = null): ConversationModelSourceUi {
    val visibleModels = conversationModels
    val models = (visibleModels + knownModels + listOfNotNull(boundModelId)).distinct()
    val setup = ProviderSetupStep.forRow(this)
    return ConversationModelSourceUi(
        id = id,
        displayName = displayName,
        displayLabel = if (bundled) ConversationModelLabel(SharedR.string.builtin_model_title) else null,
        provisioning = provisioning,
        visibleInModelPicker = visibleInModelPicker,
        chatSelectable = chatSelectable,
        setupStep = setup.toConversationStep(),
        setupLabel = setup.label(),
        knownModels = knownModels.toList(),
        choices = models.map { choice(it, it in visibleModels) },
        unavailable = unavailable(),
        catalogFeedback = catalogFeedback(),
    )
}

private fun ProviderRowUi.choice(
    model: String,
    visible: Boolean,
) = ConversationModelChoiceUi(
    id = model,
    label = modelLabel(model),
    visible = visible,
    conversationEligible = conversationEligible(model),
    selectable = modelSelectable(model),
    reasoningEfforts = reasoningOptionsFor(model).toList(),
    generationFailure =
        modelGenerations[model]?.failure?.let { failure ->
            ConversationModelFailureUi(
                failure,
                ConversationModelLabel(
                    (if (bundled) BuiltInModel.errorLabel(failure.name) else null)
                        ?: ConnectionTestMapping.codeLabel(failure),
                ),
            )
        },
    reasoningNotice =
        if (bundled && model == BuiltInModel.MODEL_ID) {
            ConversationModelLabel(R.string.builtin_reasoning_contract_notice)
        } else {
            null
        },
)

private fun ProviderRowUi.unavailable(): ConversationModelFailureUi? {
    if (chatSelectable) return null
    val code = if (bundled) builtInCatalog?.failure else (status as? ConnectionTestStatus.Failed)?.code
    val label =
        if (bundled) {
            BuiltInModel.errorLabel(code?.name) ?: R.string.builtin_model_unverified
        } else {
            R.string.provider_models_connection_required
        }
    return ConversationModelFailureUi(code, ConversationModelLabel(label))
}

private fun ProviderRowUi.catalogFeedback(): ConversationModelCatalogFeedbackUi? {
    val catalog = builtInCatalog?.takeIf { bundled } ?: return null
    val state =
        when {
            catalog.failure != null -> ConversationModelCatalogState.FAILED
            builtInTaskDeclarationMissing -> ConversationModelCatalogState.MISSING_PURPOSES
            catalog.checkedAt > 0 && conversationModels.isEmpty() -> ConversationModelCatalogState.EMPTY
            else -> null
        }
    return state?.let {
        val label =
            when (it) {
                ConversationModelCatalogState.FAILED -> R.string.builtin_model_catalog_failed
                ConversationModelCatalogState.MISSING_PURPOSES -> ConversationR.string.builtin_model_tasks_missing
                ConversationModelCatalogState.EMPTY -> R.string.builtin_model_catalog_empty
            }
        ConversationModelCatalogFeedbackUi(it, catalog.failure, ConversationModelLabel(label))
    }
}

private fun ProviderSetupStep.toConversationStep(): ConversationModelSetupStep =
    when (this) {
        ProviderSetupStep.ACCOUNT -> ConversationModelSetupStep.ACCOUNT
        ProviderSetupStep.CONNECTION -> ConversationModelSetupStep.CONNECTION
        ProviderSetupStep.MODELS -> ConversationModelSetupStep.MODELS
        ProviderSetupStep.READY -> ConversationModelSetupStep.READY
    }

private fun ProviderSetupStep.label(): ConversationModelLabel? =
    when (this) {
        ProviderSetupStep.ACCOUNT -> ConversationModelLabel(R.string.subscription_setup_account)
        ProviderSetupStep.CONNECTION -> ConversationModelLabel(R.string.subscription_setup_connection)
        ProviderSetupStep.MODELS -> ConversationModelLabel(R.string.subscription_setup_models)
        ProviderSetupStep.READY -> null
    }
