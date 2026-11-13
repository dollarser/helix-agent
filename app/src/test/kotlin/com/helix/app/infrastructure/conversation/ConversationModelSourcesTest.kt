package com.helix.app.infrastructure.conversation

import com.helix.app.R
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.BuiltInModelCatalog
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ManagedAccountSnapshot
import com.helix.app.provider.ProviderModelSelection
import com.helix.app.provider.ProviderModelVerification
import com.helix.app.provider.ProviderRowUi
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.core.model.ReasoningEffort
import com.helix.feature.conversation.presentation.ConversationModelCatalogState
import com.helix.feature.conversation.presentation.ConversationModelSetupStep
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ModelPurpose
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.ui.shared.R as SharedR

class ConversationModelSourcesTest {
    @Test
    fun choicesPreserveOrderLabelsExactReasoningAndPurposeFiltering() {
        val value =
            row()
                .copy(
                    modelSelection =
                        ProviderModelSelection(
                            models = listOf("second", "image", "first"),
                            configured = true,
                            displayNames = mapOf("second" to "Selected label"),
                        ),
                    modelMetadata =
                        mapOf(
                            "second" to ModelMetadata(reasoningEfforts = listOf(ReasoningEffort.HIGH)),
                            "image" to ModelMetadata(purposes = setOf(ModelPurpose.IMAGE)),
                            "first" to ModelMetadata(reasoningEfforts = emptyList()),
                        ),
                ).conversationModelSource()
        assertEquals(listOf("second", "first"), value.conversationModels)
        assertEquals("Selected label", value.modelLabel("second"))
        assertEquals(listOf(ReasoningEffort.OFF, ReasoningEffort.HIGH), value.reasoningOptionsFor("second"))
        assertEquals(listOf(ReasoningEffort.OFF), value.reasoningOptionsFor("first"))
        assertFalse(value.modelSelectable("image"))
        assertFalse(value.conversationEligible("image"))
        assertEquals(ConversationModelSetupStep.READY, value.setupStep)
        assertNull(value.displayLabel)
    }

    @Test
    fun reasoningContentWithoutEffortMetadataRemainsUnknownInConversationProjection() {
        // The fixture has reasoning=true, but no exact effort declaration.
        val value = row().conversationModelSource()
        assertTrue(value.reasoningOptionsFor("first").isEmpty())
        assertTrue(value.modelSelectable("first"))
    }

    @Test
    fun boundHiddenModelIsCapturedOnlyForItsOriginalProvider() {
        val hidden = ModelMetadata(purposes = setOf(ModelPurpose.CHAT))
        val first = row().copy(modelMetadata = mapOf("hidden" to hidden))
        val second = first.copy(id = "another")
        val snapshot = listOf(first, second).conversationModelSources(first.id, "hidden")
        assertFalse("hidden" in snapshot[0].conversationModels)
        assertTrue(snapshot[0].conversationEligible("hidden"))
        assertTrue(snapshot[0].modelSelectable("hidden"))
        assertNull(snapshot[1].choice("hidden"))
        assertEquals("unknown", snapshot[0].modelLabel("unknown"))
        assertFalse(snapshot[0].modelSelectable("unknown"))
    }

    @Test
    fun revokedAccountCannotBecomeVisibleOrSelectableFromOldConnectionEvidence() {
        val value =
            row()
                .copy(
                    provisioning = ProviderProvisioningKind.MANAGED_ACCOUNT,
                    accountState = ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_OUT),
                ).conversationModelSource()
        assertFalse(value.visibleInModelPicker)
        assertFalse(value.chatSelectable)
        assertFalse(value.modelSelectable("first"))
        assertEquals(ConversationModelSetupStep.ACCOUNT, value.setupStep)
        assertEquals(R.string.subscription_setup_account, value.setupLabel?.resourceId)
        assertTrue(value.reasoningOptionsFor("first").isEmpty())
    }

    @Test
    fun exactGenerationFailureAndConnectionFailureKeepTheirDifferentFacts() {
        val failure = ProviderModelVerification(1, failure = ModelErrorCode.PROTOCOL)
        val failedModel =
            row()
                .copy(
                    modelGenerations = mapOf("first" to failure),
                ).conversationModelSource()
        assertTrue(failedModel.chatSelectable)
        assertFalse(failedModel.modelSelectable("first"))
        assertEquals(ModelErrorCode.PROTOCOL, failedModel.choice("first")?.generationFailure?.code)
        assertEquals(ConversationModelSetupStep.MODELS, failedModel.setupStep)
        assertNull(failedModel.unavailable)

        val failedConnection =
            row()
                .copy(
                    status = ConnectionTestStatus.Failed(1, 1, ModelErrorCode.AUTH, false),
                ).conversationModelSource()
        assertFalse(failedConnection.chatSelectable)
        assertEquals(ModelErrorCode.AUTH, failedConnection.unavailable?.code)
        val unavailable = requireNotNull(failedConnection.unavailable)
        assertEquals(R.string.provider_models_connection_required, unavailable.label.resourceId)
        assertEquals(ConversationModelSetupStep.CONNECTION, failedConnection.setupStep)
    }

    @Test
    fun bundledCatalogFailureRetainsDeclaredSelectionAndExactFeedback() {
        val purposes = listOf(BuiltInModel.MODEL_ID, "other").associateWith { setOf(ModelPurpose.CHAT) }
        val value =
            row()
                .copy(
                    id = BuiltInModel.PROVIDER_ID,
                    provisioning = ProviderProvisioningKind.BUNDLED_SERVICE,
                    status = ConnectionTestStatus.Untested,
                    builtInCatalog =
                        BuiltInModelCatalog(
                            checkedAt = 1,
                            models = listOf(BuiltInModel.MODEL_ID, "other"),
                            failure = ModelErrorCode.TIMEOUT,
                            purposes = purposes,
                        ),
                ).conversationModelSource()
        assertTrue(value.chatSelectable)
        assertTrue(value.modelSelectable(BuiltInModel.MODEL_ID))
        assertEquals(ConversationModelCatalogState.FAILED, value.catalogFeedback?.state)
        assertEquals(ModelErrorCode.TIMEOUT, value.catalogFeedback?.failure)
        assertEquals(SharedR.string.builtin_model_title, value.displayLabel?.resourceId)
        val bundledChoice = requireNotNull(value.choice(BuiltInModel.MODEL_ID))
        assertEquals(R.string.builtin_reasoning_contract_notice, bundledChoice.reasoningNotice?.resourceId)
        assertNull(requireNotNull(value.choice("other")).reasoningNotice)
        assertEquals(ReasoningEffort.FALLBACK, value.reasoningOptionsFor(BuiltInModel.MODEL_ID))
        assertTrue(value.reasoningOptionsFor("other").isEmpty())
    }

    @Test
    fun missingPurposeAndEmptyCatalogAreNotReportedAsTransportFailures() {
        val bundled = row().copy(provisioning = ProviderProvisioningKind.BUNDLED_SERVICE)
        val missingCatalog = BuiltInModelCatalog(1, listOf("unknown"))
        val missing = bundled.copy(builtInCatalog = missingCatalog).conversationModelSource()
        assertTrue(missing.conversationModels.isEmpty())
        assertEquals(ConversationModelCatalogState.MISSING_PURPOSES, missing.catalogFeedback?.state)
        assertNull(missing.catalogFeedback?.failure)
        val empty = bundled.copy(builtInCatalog = BuiltInModelCatalog(1)).conversationModelSource()
        assertEquals(ConversationModelCatalogState.EMPTY, empty.catalogFeedback?.state)
        assertFalse(empty.chatSelectable)
        assertNull(empty.unavailable?.code)
        assertNull(bundled.conversationModelSource().catalogFeedback)
    }

    @Test
    fun snapshotDoesNotFollowLaterListEdits() {
        val selected = mutableListOf("first")
        val backend = mutableListOf("listed")
        val source = row().copy(modelSelection = ProviderModelSelection(selected, true), backendModels = backend)
        val snapshot = source.conversationModelSource()
        selected += "later"
        backend += "new-backend"
        assertEquals(listOf("first"), snapshot.conversationModels)
        assertFalse("later" in snapshot.knownModels)
        assertFalse("new-backend" in snapshot.knownModels)
        assertNull(snapshot.choice("later"))
    }

    private fun row(): ProviderRowUi {
        val capabilities = ProviderCapabilities(true, true, false, false, true, false, 8192, CapabilitySource.PROBED)
        return ProviderRowUi(
            id = "source",
            displayName = "Source",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            origin = "https://example.invalid",
            residence = ProviderResidence.PUBLIC_CLOUD,
            model = "first",
            hasKey = true,
            isCleartext = false,
            status = ConnectionTestStatus.Passed(1, capabilities),
            capabilities = capabilities,
            backendModels = null,
            modelSelection = ProviderModelSelection(listOf("first"), true),
        )
    }
}
