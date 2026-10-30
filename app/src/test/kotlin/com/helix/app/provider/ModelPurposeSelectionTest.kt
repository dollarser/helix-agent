package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ModelPurpose
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPurposeSelectionTest {
    private val caps =
        ProviderCapabilities(
            true,
            false,
            false,
            false,
            false,
            false,
            null,
            CapabilitySource.CONNECTION_ONLY,
        )

    private fun row(bundled: Boolean = false) =
        ProviderRowUi(
            "source",
            "Source",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://fixture.test/v1",
            ProviderResidence.PUBLIC_CLOUD,
            "alias",
            false,
            false,
            ConnectionTestStatus.Passed(1, caps, listOf("alias", "vector", "multi")),
            caps,
            listOf("alias", "vector", "multi"),
            provisioning =
                if (bundled) {
                    ProviderProvisioningKind.BUNDLED_SERVICE
                } else {
                    ProviderProvisioningKind.USER_CONFIGURED
                },
        )

    @Test fun unknownRequiresExplicitChoiceWithoutPretendingToBeServerMetadata() {
        val unknown = row()
        assertTrue(unknown.conversationModels.isEmpty())
        assertFalse(unknown.modelSelectable("alias"))
        val selected = unknown.copy(modelSelection = ProviderModelSelection().addCustom("alias"))
        assertEquals(listOf("alias"), selected.conversationModels)
        assertTrue(selected.modelSelectable("alias"))
        assertTrue(selected.purposesFor("alias").isEmpty())
        assertFalse(selected.conversationProhibited("alias"))
    }

    @Test fun explicitNonChatOverridesOldSelectionsAndMultiTaskModelsRemainUsable() {
        val current =
            row().copy(
                modelSelection = ProviderModelSelection(listOf("alias", "vector", "multi"), configured = true),
                modelMetadata =
                    mapOf(
                        "alias" to ModelMetadata(purposes = setOf(ModelPurpose.IMAGE)),
                        "vector" to ModelMetadata(purposes = setOf(ModelPurpose.EMBEDDING)),
                        "multi" to ModelMetadata(purposes = setOf(ModelPurpose.CHAT, ModelPurpose.EMBEDDING)),
                    ),
            )
        assertEquals(listOf("multi"), current.conversationModels)
        assertFalse(current.modelSelectable("vector"))
        assertTrue(current.conversationProhibited("vector"))
        assertTrue(current.modelSelectable("multi"))
        for (purpose in listOf(ModelPurpose.RERANK, ModelPurpose.AUDIO, ModelPurpose.VIDEO, ModelPurpose.OTHER)) {
            assertTrue(
                current
                    .copy(
                        modelMetadata = mapOf("alias" to ModelMetadata(purposes = setOf(purpose))),
                    ).conversationProhibited("alias"),
            )
        }
    }

    @Test fun accountCatalogEnablesChatWithoutManualChoices() {
        val current =
            row(true).copy(
                builtInCatalog =
                    BuiltInModelCatalog(
                        1,
                        listOf("alias", "vector", "multi"),
                        purposes =
                            mapOf(
                                "vector" to setOf(ModelPurpose.EMBEDDING),
                                "multi" to setOf(ModelPurpose.CHAT),
                            ),
                    ),
            )
        assertEquals(listOf("multi"), current.conversationModels)
        val selected =
            current.copy(
                modelSelection = ProviderModelSelection().toggle("alias", true).toggle("vector", true),
            )
        assertEquals(listOf("multi"), selected.conversationModels)
        assertFalse(selected.modelSelectable("alias"))
        assertTrue(
            selected
                .copy(
                    modelSelection = ProviderModelSelection(emptyList(), configured = true),
                ).modelSelectable("multi"),
        )
        assertFalse(selected.modelSelectable("vector"))
        assertTrue(selected.copy(builtInCatalog = BuiltInModelCatalog()).conversationModels.isEmpty())
        assertTrue(
            current
                .copy(
                    builtInCatalog = BuiltInModelCatalog(failure = ModelErrorCode.AUTH),
                ).conversationModels
                .isEmpty(),
        )
        val overriddenContract =
            row(true).copy(
                builtInCatalog =
                    BuiltInModelCatalog(
                        1,
                        listOf(BuiltInModel.MODEL_ID),
                        purposes =
                            mapOf(BuiltInModel.MODEL_ID to setOf(ModelPurpose.EMBEDDING)),
                    ),
            )
        assertFalse(overriddenContract.modelSelectable(BuiltInModel.MODEL_ID))
    }

    @Test fun accountLoginAndRefreshPublishChatCandidatesWithoutAnySavedChoices() =
        kotlinx.coroutines.runBlocking {
            var identity = "signed-out"
            var now = 1000L
            var calls = 0
            var directory =
                ModelCatalogResult.Listed(
                    listOf("chat-alias", "vector", "untyped"),
                    mapOf("chat-alias" to setOf(ModelPurpose.CHAT), "vector" to setOf(ModelPurpose.EMBEDDING)),
                )
            val store = BuiltInModelCatalogStore(InMemoryLineStore()) { identity }
            val provider =
                object : com.helix.provider.api.ModelProvider {
                    override val descriptor =
                        com.helix.provider.api.ProviderDescriptor(
                            BuiltInModel.PROVIDER_ID,
                            "Built-in",
                            BuiltInModel.connection,
                            BuiltInModel.MODEL_ID,
                        )

                    override suspend fun listModels(): ModelCatalogResult {
                        calls++
                        return directory
                    }

                    override suspend fun validateConfiguration(): com.helix.provider.api.ProviderCheckResult =
                        error("No health probe")

                    override fun stream(request: com.helix.core.model.ModelRequest): Nothing = error("No generation")
                }
            val discovery =
                BuiltInModelDiscovery(
                    store,
                    object : com.helix.core.model.Clock {
                        override fun now() = java.time.Instant.ofEpochMilli(now)
                    },
                    { provider },
                    {},
                )
            discovery.refresh(false)
            assertEquals(0, calls)
            assertTrue(row(true).copy(builtInCatalog = discovery.current()).conversationModels.isEmpty())
            identity = "synthetic-account"
            discovery.refresh(false)
            val oldChoices = ProviderModelSelection(listOf("vector"), configured = true)
            val current = row(true).copy(builtInCatalog = discovery.current(), modelSelection = oldChoices)
            assertEquals(listOf("chat-alias"), current.conversationModels)
            assertTrue(current.modelSelectable("chat-alias"))
            assertFalse(current.modelSelectable("vector"))
            assertFalse(current.modelSelectable("untyped"))
            now += BuiltInModelCatalog.SUCCESS_TTL + 1
            directory =
                directory.copy(
                    models = directory.models + "fresh-chat",
                    purposes =
                        directory.purposes + ("fresh-chat" to setOf(ModelPurpose.CHAT)),
                )
            discovery.refresh(false)
            assertEquals(
                listOf("chat-alias", "fresh-chat"),
                current.copy(builtInCatalog = discovery.current()).conversationModels,
            )
            assertEquals(2, calls)
        }

    @Test fun builtInFeedbackDistinguishesUnclassifiedEmptyAndFailedDirectories() {
        val missing = row(true).copy(builtInCatalog = BuiltInModelCatalog(1, listOf("unclassified")))
        assertEquals(
            com.helix.app.R.string.builtin_model_tasks_missing,
            com.helix.app.ui
                .builtInCatalogMessage(missing),
        )
        assertEquals(
            com.helix.app.R.string.builtin_model_catalog_empty,
            com.helix.app.ui
                .builtInCatalogMessage(missing.copy(builtInCatalog = BuiltInModelCatalog(1))),
        )
        assertEquals(
            com.helix.app.R.string.builtin_model_catalog_failed,
            com.helix.app.ui.builtInCatalogMessage(
                missing.copy(builtInCatalog = BuiltInModelCatalog(failure = ModelErrorCode.TRANSPORT)),
            ),
        )
        val known = missing.copy(builtInCatalog = BuiltInModelCatalog(1, listOf(BuiltInModel.MODEL_ID)))
        assertEquals(
            com.helix.app.R.string.builtin_model_tasks_missing,
            com.helix.app.ui
                .builtInCatalogMessage(known),
        )
        assertFalse(known.modelSelectable(BuiltInModel.MODEL_ID))
        assertEquals(
            null,
            com.helix.app.ui
                .builtInCatalogMessage(row()),
        )
    }

    @Test fun unknownRefreshPreservesExplicitTasksUntilRedeclared() {
        val store = ProviderModelMetadataStore(InMemoryLineStore())
        store.catalog(
            "p",
            "endpoint",
            ModelCatalogResult.Listed(
                listOf("alias"),
                mapOf("alias" to setOf(ModelPurpose.EMBEDDING)),
            ),
        )
        store.catalog("p", "endpoint", ModelCatalogResult.Listed(listOf("alias", "new")))
        assertEquals(setOf(ModelPurpose.EMBEDDING), store.read("p", "endpoint")["alias"]?.purposes)
        assertTrue(store.read("p", "endpoint")["new"]!!.purposes.isEmpty())
        store.merge("p", "endpoint", mapOf("alias" to ModelMetadata(vision = true)))
        assertEquals(setOf(ModelPurpose.EMBEDDING), store.read("p", "endpoint")["alias"]?.purposes)
        store.catalog(
            "p",
            "endpoint",
            ModelCatalogResult.Listed(
                listOf("alias"),
                mapOf("alias" to setOf(ModelPurpose.CHAT)),
            ),
        )
        assertEquals(setOf(ModelPurpose.CHAT), store.read("p", "endpoint")["alias"]?.purposes)
    }

    @Test fun publicTaskEvidencePersistsPerEndpointAndNeverBecomesUserPreference() {
        val backing = InMemoryLineStore()
        val store = ProviderModelMetadataStore(backing)
        val metadata = mapOf("alias" to ModelMetadata(purposes = setOf(ModelPurpose.RERANK)))
        store.write("p", "endpoint", metadata)
        assertEquals(metadata, ProviderModelMetadataStore(backing).read("p", "endpoint"))
        assertTrue(store.read("p", "different-endpoint").isEmpty())
        assertFalse(ProviderSelectedModels(backing).selection("p").configured)
        val catalogStore = BuiltInModelCatalogStore(backing) { "synthetic-account" }
        val catalog = BuiltInModelCatalog(100, listOf("alias"), purposes = mapOf("alias" to setOf(ModelPurpose.RERANK)))
        catalogStore.save(catalog)
        assertEquals(catalog, catalogStore.read())
    }
}
