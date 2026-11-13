package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.app.internal.LineStore
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Real Room/Keystore, synthetic keys and no network. All databases belong to the isolated fixture. */
class ProviderDraftSaveDeviceTest {
    private val draft =
        ProviderDraft(
            null,
            "Draft fixture",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            NormalizedEndpoint.parse("https://example.test/v1"),
            "a",
            "{}",
            false,
        )
    private val selection = ProviderModelSelection(listOf("a", "b"), true, listOf("b"))

    @Test fun wholeDraftPersistsAndCompletedRetryDoesNotCreateAgain() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                val session = f.service.beginDraftSave()
                val id = f.service.saveDraft(session, draft, "synthetic-original", selection)
                assertEquals(id, f.service.saveDraft(session, draft, "synthetic-original", selection))
                assertEquals(
                    2,
                    f.storage.providerConfigs
                        .list()
                        .size,
                )
                assertEquals(selection, f.status.selectedModels.selection(id))
                assertEquals("synthetic-original", f.service.revealUserConfiguredApiKey(id))
                assertEquals(0, f.generations)
                f.service.delete(id)
            }
        }

    @Test fun blankKeyRetainsAndReplacementRotatesTheAlias() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                val id = f.service.saveDraft(f.service.beginDraftSave(), draft, "synthetic-original", selection)
                val original = f.service.storedConfig(id)
                val edit = f.service.beginDraftSave(id, original, selection)
                f.service.saveDraft(edit, draft.copy(displayName = "Renamed"), "", selection)
                assertEquals(original.auth, f.service.storedConfig(id).auth)
                assertEquals("synthetic-original", f.service.revealUserConfiguredApiKey(id))
                val rotate = f.service.beginDraftSave(id, f.service.storedConfig(id), selection)
                f.service.saveDraft(rotate, draft, "synthetic-replacement", selection)
                assertNotEquals(original.auth, f.service.storedConfig(id).auth)
                assertFalse(f.storage.secrets.contains((original.auth as ProviderAuth.Secret).alias))
                assertEquals("synthetic-replacement", f.service.revealUserConfiguredApiKey(id))
                f.service.delete(id)
            }
        }

    @Test fun changedConnectionOrSelectionRejectsTheStaleForm() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                val id = f.service.saveDraft(f.service.beginDraftSave(), draft, null, selection)
                val first = f.service.beginDraftSave(id, f.service.storedConfig(id), selection)
                f.service.update(id, draft.copy(displayName = "Other editor"), null)
                assertTrue(
                    runCatching { f.service.saveDraft(first, draft, null, selection) }
                        .exceptionOrNull() is ProviderDraftSaveConflictException,
                )
                val second = f.service.beginDraftSave(id, f.service.storedConfig(id), selection)
                f.service.saveSelectedModels(id, listOf("b"))
                assertTrue(
                    runCatching { f.service.saveDraft(second, draft, null, selection) }
                        .exceptionOrNull() is ProviderDraftSaveConflictException,
                )
                assertEquals(listOf("b"), f.status.selectedModels.read(id))
            }
        }

    @Test fun ownProbeEvidenceDoesNotConflictWithLaterSave() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                val original = f.service.storedConfig("p")
                val choices = f.status.selectedModels.selection("p")
                val session = f.service.beginDraftSave("p", original, choices)
                f.service.runConnectionTest("p", "a", expectedConfig = original)
                val evidence = f.service.storedConfig("p").capabilitySnapshot
                f.service.saveDraft(session, draft.copy(displayName = "Renamed"), null, selection)
                assertEquals(evidence, f.service.storedConfig("p").capabilitySnapshot)
                assertEquals(selection, f.status.selectedModels.selection("p"))
            }
        }

    @Test fun failedSelectionWriteRollsBackAndRetriesWithTheSameId() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                val originalAliases = f.storage.secrets.aliases()
                val lines = FailingSelectionStore()
                val status = ProviderTestStatusStore(lines)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val service =
                        ProviderService(
                            storage = f.storage,
                            factory =
                                ProviderFactory(
                                    CredentialLookup { error("No network credentials") },
                                    object : WireClient {
                                        override suspend fun open(request: WireRequest): WireResponse {
                                            error("No network")
                                        }
                                    },
                                    { error("No images") },
                                ),
                            testStatus = status,
                            idGenerator = { UUID.randomUUID().toString() },
                            scope = scope,
                        )
                    val session = service.beginDraftSave()
                    lines.failNextSelection = true
                    assertTrue(
                        runCatching {
                            service.saveDraft(session, draft, "synthetic-key", selection)
                        }.isFailure,
                    )
                    assertEquals(
                        1,
                        f.storage.providerConfigs
                            .list()
                            .size,
                    )
                    assertEquals(ProviderModelSelection(), status.selectedModels.selection(session.providerId))
                    assertEquals(originalAliases, f.storage.secrets.aliases())
                    val savedId = service.saveDraft(session, draft, "synthetic-key", selection)
                    assertEquals(session.providerId, savedId)
                    assertEquals(
                        2,
                        f.storage.providerConfigs
                            .list()
                            .size,
                    )
                    assertEquals(selection, status.selectedModels.selection(session.providerId))
                    service.delete(session.providerId)
                } finally {
                    scope.cancel()
                }
            }
        }

    private class FailingSelectionStore : LineStore {
        private val backing = InMemoryLineStore()
        var failNextSelection = false

        override fun lines(key: String) = backing.lines(key)

        override fun setLines(
            key: String,
            lines: List<String>,
        ) {
            backing.setLines(key, lines)
            if (key.startsWith("provider-model-selection-") && failNextSelection) {
                failNextSelection = false
                error("Injected failure after selection write")
            }
        }
    }
}
