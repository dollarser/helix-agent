package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProbeOutcome
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderCheckResult
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.ProviderDescriptor
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Room and service wiring with a synthetic provider. No network or model execution. */
class ProviderModelsIntegrationDeviceTest {
    @Test fun choicesAndDefaultsDoNotResetConnectionOrExistingSessions() =
        runBlocking {
            Fixture().use { f ->
                f.storage.sessions.create("s", "Session", "p", "a", 1)
                f.status.recordPassed("p", 1, CAPS, listOf("a", "b"))
                val selected = ProviderModelSelection(listOf("b"), configured = true)
                f.service.saveModelSelection("p", selected)
                assertTrue(f.service.chatSelectable("p"))
                assertEquals(
                    "a",
                    f.storage.sessions
                        .resolve("s")
                        .modelId,
                )
                assertEquals(
                    "a",
                    f.storage.providerConfigs
                        .resolve("p")
                        .model,
                )
                assertEquals(
                    listOf("a", "b"),
                    f.service.rows.value
                        .single()
                        .backendModels,
                )
                assertEquals(
                    listOf("b"),
                    f.service.rows.value
                        .single()
                        .conversationModels,
                )
                f.service.saveSelectedModels("p", emptyList())
                assertTrue(
                    f.service.rows.value
                        .single()
                        .conversationModels
                        .isEmpty(),
                )
                assertTrue(f.service.chatSelectable("p"))
            }
        }

    @Test fun exactModelProbeAndCatalogAreIndependent() =
        runBlocking {
            Fixture().use { f ->
                f.status.recordPassed("p", 1, CAPS.copy(toolCalls = false), listOf("a", "b"))
                f.service.saveSelectedModels("p", listOf("b"))
                assertTrue(f.service.runCapabilityTest("p", "b") is ProbeOutcome.Ok)
                assertEquals(listOf("b"), f.probed)
                assertEquals(
                    "a",
                    f.storage.providerConfigs
                        .resolve("p")
                        .model,
                )
                val row =
                    f.service.rows.value
                        .single()
                assertTrue(row.capabilitiesForModel("b")?.toolCalls == true)
                assertFalse(row.capabilitiesForModel("a")?.toolCalls == true)
                assertTrue(f.service.refreshModelCatalog("p") is ModelCatalogResult.Listed)
                assertEquals(
                    listOf("b"),
                    f.service.rows.value
                        .single()
                        .conversationModels,
                )
                assertEquals(0, f.generations)
            }
        }

    @Test fun configurationChangeRejectsALatePerModelProbe() =
        runBlocking {
            Fixture().use { f ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.probePause = {
                    entered.complete(Unit)
                    release.await()
                }
                val old = async { f.service.runCapabilityTest("p", "b") }
                entered.await()
                val config = f.service.storedConfig("p")
                val endpoint = NormalizedEndpoint.parse("https://changed.test/v1")
                val draft =
                    ProviderDraft(
                        templateId = null,
                        displayName = "Changed",
                        protocol = config.protocol,
                        endpoint = endpoint,
                        model = config.model,
                        headersJson = "{}",
                        credentialRequired = false,
                    )
                f.service.update("p", draft, null)
                release.complete(Unit)
                assertTrue(old.await() is ProbeOutcome.Failed)
                assertTrue(
                    f.service.rows.value
                        .single()
                        .modelVerifications
                        .isEmpty(),
                )
                assertFalse(f.service.chatSelectable("p"))
            }
        }

    @Test fun baseModelFailureKeepsSiblingUsableAndCapabilityFailureDoesNotRevokeGeneration() =
        runBlocking {
            Fixture().use { f ->
                f.service.saveSelectedModels("p", listOf("a", "b"))
                check(f.service.runConnectionTest("p", "b", verifyGeneration = true) is ProbeOutcome.Ok)
                check(f.service.runCapabilityTest("p", "b") is ProbeOutcome.Ok)
                f.generationFailure = ModelErrorCode.PROTOCOL
                check(f.service.runConnectionTest("p", "a", verifyGeneration = true) is ProbeOutcome.Failed)
                assertTrue(f.service.chatSelectable("p"))
                assertFalse(
                    f.service.rows.value
                        .single()
                        .modelSelectable("a"),
                )
                assertTrue(
                    f.service.rows.value
                        .single()
                        .modelSelectable("b"),
                )
                f.capabilityFailure = ModelErrorCode.PROTOCOL
                check(f.service.runCapabilityTest("p", "b") is ProbeOutcome.Failed)
                f.generationFailure = null
                check(f.service.runConnectionTest("p", "b", verifyGeneration = true) is ProbeOutcome.Ok)
                val row =
                    f.service.rows.value
                        .single()
                assertTrue(row.modelSelectable("b"))
                assertEquals(ModelErrorCode.PROTOCOL, row.modelVerifications.getValue("b").failure)
                assertEquals(listOf("a", "b"), row.conversationModels)
            }
        }

    @Test fun replacingAccountClearsOldEvidenceButPreservesSelectionAndSessions() =
        runBlocking {
            Fixture(managed = true).use { f ->
                f.service.saveSelectedModels("p", listOf("b"))
                f.storage.sessions.create("s", "Session", "p", "b", 1)
                check(f.service.runCapabilityTest("p", "a") is ProbeOutcome.Ok)
                check(f.service.runCapabilityTest("p", "b") is ProbeOutcome.Ok)
                val old = f.service.modelProviderFor("p", "b")
                assertEquals("b", old.descriptor.model)
                f.login = f.login.copy(revision = SECOND_LOGIN)
                f.service.refreshManagedAccounts()
                assertFalse(f.service.chatSelectable("p"))
                assertTrue(
                    f.service.rows.value
                        .single()
                        .modelVerifications
                        .isEmpty(),
                )
                assertTrue(
                    f.service.rows.value
                        .single()
                        .modelGenerations
                        .isEmpty(),
                )
                assertEquals(
                    listOf("b"),
                    f.service.rows.value
                        .single()
                        .conversationModels,
                )
                assertEquals(
                    "b",
                    f.storage.sessions
                        .resolve("s")
                        .modelId,
                )
                check(f.service.runConnectionTest("p", "b") is ProbeOutcome.Ok)
                assertFalse(
                    f.service.rows.value
                        .single()
                        .capabilitiesForModel("a")
                        ?.toolCalls == true,
                )
                val count = f.generations
                val request = ModelRequest("b", listOf(ModelMessage(ModelRole.USER, "next")))
                assertEquals(ModelErrorCode.AUTH, (old.stream(request).toList().single() as ModelEvent.Error).code)
                assertEquals(count, f.generations)
            }
        }

    @Test fun accountChangeRejectsAnInFlightModelProbe() =
        runBlocking {
            Fixture(managed = true).use { f ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.probePause = {
                    entered.complete(Unit)
                    release.await()
                }
                val old = async { f.service.runCapabilityTest("p", "b") }
                entered.await()
                f.login = f.login.copy(revision = SECOND_LOGIN)
                f.service.refreshManagedAccounts()
                release.complete(Unit)
                assertTrue(old.await() is ProbeOutcome.Failed)
                assertFalse(f.service.chatSelectable("p"))
                assertTrue(
                    f.service.rows.value
                        .single()
                        .modelVerifications
                        .isEmpty(),
                )
            }
        }

    @Test fun nonDefaultFactoryAndRequestsUseTheSameModel() =
        runBlocking {
            Fixture().use { f ->
                f.service.saveSelectedModels("p", listOf("b"))
                check(f.service.runConnectionTest("p", "b") is ProbeOutcome.Ok)
                val provider = f.service.modelProviderFor("p", "b")
                val request = ModelRequest("b", listOf(ModelMessage(ModelRole.USER, "retained context")))
                f.requests.clear()
                provider.stream(request).toList()
                provider
                    .stream(
                        request.copy(messages = request.messages + ModelMessage(ModelRole.USER, "continue")),
                    ).toList()
                assertEquals(listOf("b", "b"), f.requests.map { it.model })
                assertEquals("b", provider.descriptor.model)
                assertEquals(
                    "a",
                    f.storage.providerConfigs
                        .resolve("p")
                        .model,
                )
            }
        }

    @Test fun directoryConnectionPublishesCatalogWithoutModelEvidenceOrExtraRequests() =
        runBlocking {
            for (catalog in listOf(
                ModelCatalogResult.Listed(emptyList()),
                ModelCatalogResult.Listed(listOf("embedding-only")),
            )) {
                Fixture().use { f ->
                    f.catalog = catalog
                    assertTrue(f.service.runConnectionTest("p") is ProbeOutcome.Ok)
                    assertEquals(1, f.catalogCalls)
                    assertEquals(0, f.generations)
                    assertEquals(0, f.contextCalls)
                    assertEquals(0, f.metadataCalls)
                    assertEquals(emptyList<String>(), f.probed)
                    val row =
                        f.service.rows.value
                            .single()
                    assertTrue(row.status is ConnectionTestStatus.Passed)
                    assertEquals(catalog.models, row.backendModels)
                    assertTrue(row.modelGenerations.isEmpty())
                    assertTrue(row.modelVerifications.isEmpty())
                }
            }
        }

    @Test fun directoryConnectionPreservesPreviouslyDiscoveredModelContext() =
        runBlocking {
            Fixture().use { f ->
                f.contextWindow = 8192L
                assertEquals(8192L, f.service.discoverContextWindow("p", "a").serverWindow)
                f.contextCalls = 0
                f.metadataCalls = 0
                assertTrue(f.service.runConnectionTest("p") is ProbeOutcome.Ok)
                assertEquals(0, f.contextCalls)
                assertEquals(0, f.metadataCalls)
                assertEquals(8192L, f.service.contextSettings("p", "a").serverWindow)
            }
        }

    @Test fun failedDirectoryDoesNotPublishModelFailureOrMakeExtraRequests() =
        runBlocking {
            for (catalog in listOf(
                ModelCatalogResult.Unsupported,
                ModelCatalogResult.Failed(ModelErrorCode.AUTH, "HTTP 401", false),
                ModelCatalogResult.Failed(ModelErrorCode.TIMEOUT, "catalog timeout", true),
            )) {
                Fixture().use { f ->
                    f.catalog = catalog
                    val outcome = f.service.runConnectionTest("p") as ProbeOutcome.Failed
                    if (catalog is ModelCatalogResult.Failed) {
                        assertEquals(catalog.code, outcome.code)
                        assertEquals(catalog.detail, outcome.detail)
                        assertEquals(catalog.retryable, outcome.retryable)
                    } else {
                        assertEquals("MODEL_CATALOG_UNSUPPORTED", outcome.detail)
                    }
                    assertEquals(1, f.catalogCalls)
                    assertEquals(0, f.generations)
                    assertEquals(0, f.contextCalls)
                    assertEquals(0, f.metadataCalls)
                    val row =
                        f.service.rows.value
                            .single()
                    assertTrue(row.modelGenerations.isEmpty())
                    assertTrue(row.modelVerifications.isEmpty())
                    assertFalse(f.service.chatSelectable("p"))
                }
            }
        }

    @Test fun cancelledDirectoryDoesNotPublishOrStartAnotherRequest() =
        runBlocking {
            Fixture().use { f ->
                f.service.refresh()
                val initial =
                    kotlinx.coroutines.withTimeout(5000) {
                        f.service.rows
                            .first { rows -> rows.any { it.id == "p" } }
                            .single()
                    }
                val entered = CompletableDeferred<Unit>()
                f.catalogPause = {
                    entered.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
                val test = async { f.service.runConnectionTest("p") }
                entered.await()
                test.cancel()
                test.join()
                assertTrue(test.isCancelled)
                assertEquals(1, f.catalogCalls)
                assertEquals(0, f.generations)
                assertEquals(0, f.contextCalls)
                assertEquals(0, f.metadataCalls)
                val row =
                    f.service.rows.value
                        .single()
                assertEquals(initial, row)
                assertFalse(row.status is ConnectionTestStatus.Passed)
                assertTrue(row.modelGenerations.isEmpty())
                assertTrue(row.modelVerifications.isEmpty())
            }
        }

    @Test fun unsupportedDirectoryDoesNotRevokeIndependentModelGeneration() =
        runBlocking {
            Fixture().use { f ->
                f.catalog = ModelCatalogResult.Unsupported
                val first = f.service.runConnectionTest("p") as ProbeOutcome.Failed
                assertEquals("MODEL_CATALOG_UNSUPPORTED", first.detail)
                assertTrue((f.status.statusFor("p") as ConnectionTestStatus.Failed).catalogUnsupported)
                assertEquals(0, f.generations)
                assertTrue(f.service.runConnectionTest("p", "b", verifyGeneration = true) is ProbeOutcome.Ok)
                val passed = f.status.statusFor("p") as ConnectionTestStatus.Passed
                val generation =
                    f.service.rows.value
                        .single()
                        .modelGenerations
                        .getValue("b")
                val repeated = f.service.runConnectionTest("p") as ProbeOutcome.Failed
                assertEquals("MODEL_CATALOG_UNSUPPORTED", repeated.detail)
                assertEquals(passed, f.status.statusFor("p"))
                assertEquals(
                    generation,
                    f.service.rows.value
                        .single()
                        .modelGenerations
                        .getValue("b"),
                )
                assertEquals(1, f.generations)
                assertEquals(3, f.catalogCalls)
            }
        }

    @Test fun staleContextConfigurationCannotLoadDiscoverOrWriteAcrossEndpoints() =
        runBlocking {
            Fixture().use { f ->
                val original = f.service.storedConfig("p")
                f.service.saveContextSettings(
                    "p",
                    "a",
                    com.helix.provider.api
                        .ProviderContextSettings(manualWindow = 8192L),
                    expectedConfig = original,
                )
                f.contextWindow = 16384L
                assertEquals(16384L, f.service.discoverContextWindow("p", "a", expectedConfig = original).serverWindow)
                val oldSettings = f.service.contextSettingsStore.read("p", original.transport.cacheKey, "a")
                changeEndpoint(f, original)
                val current = f.service.storedConfig("p")
                f.service.saveContextSettings(
                    "p",
                    "a",
                    com.helix.provider.api
                        .ProviderContextSettings(manualWindow = 32768L),
                    expectedConfig = current,
                )
                val newSettings = f.service.contextSettingsStore.read("p", current.transport.cacheKey, "a")
                val calls = f.contextCalls
                val staleOperations: List<suspend () -> Unit> =
                    listOf(
                        {
                            f.service.contextSettings("p", "a", expectedConfig = original)
                            Unit
                        },
                        {
                            f.service.discoverContextWindow("p", "a", expectedConfig = original)
                            Unit
                        },
                        {
                            f.service.saveContextSettings(
                                "p",
                                "a",
                                com.helix.provider.api
                                    .ProviderContextSettings(manualWindow = 65536L),
                                expectedConfig = original,
                            )
                        },
                    )
                for (operation in staleOperations) {
                    try {
                        operation()
                        org.junit.Assert.fail("Stale context operation must reject its original configuration")
                    } catch (_: ProviderDraftSaveConflictException) {
                        // The explicit conflict is required; an arbitrary failure is not sufficient.
                    }
                }
                assertEquals(calls, f.contextCalls)
                assertEquals(oldSettings, f.service.contextSettingsStore.read("p", original.transport.cacheKey, "a"))
                assertEquals(newSettings, f.service.contextSettingsStore.read("p", current.transport.cacheKey, "a"))
                assertEquals(32768L, f.service.contextSettings("p", "a", expectedConfig = current).manualWindow)
            }
        }

    @Test fun staleModelDialogCannotSaveSelectionOrDiscoverAnotherEndpoint() =
        runBlocking {
            Fixture().use { f ->
                f.service.saveSelectedModels("p", listOf("a"))
                val original = f.service.storedConfig("p")
                val baseline =
                    f.service.rows.value
                        .single()
                        .modelSelection
                changeEndpoint(f, original)
                val selected = ProviderModelSelection(listOf("b"), configured = true)
                assertConfigurationConflict {
                    f.service.saveModelSelection("p", selected, baseline, expectedConfig = original)
                }
                assertConfigurationConflict {
                    f.service.refreshModelCatalog("p", expectedConfig = original)
                }
                assertEquals(
                    baseline,
                    f.service.rows.value
                        .single()
                        .modelSelection,
                )
                assertEquals(0, f.catalogCalls)
                assertEquals(0, f.metadataCalls)
                assertEquals(0, f.contextCalls)
                assertEquals(0, f.generations)
                val current = f.service.storedConfig("p")
                f.service.saveModelSelection("p", selected, baseline, expectedConfig = current)
                assertEquals(
                    selected,
                    f.service.rows.value
                        .single()
                        .modelSelection,
                )
                assertTrue(f.service.refreshModelCatalog("p", expectedConfig = current) is ModelCatalogResult.Listed)
                assertEquals(1, f.catalogCalls)
            }
        }

    private suspend fun changeEndpoint(
        fixture: Fixture,
        original: ProviderConfig,
    ) {
        fixture.service.update(
            "p",
            ProviderDraft(
                templateId = null,
                displayName = original.displayName,
                protocol = original.protocol,
                endpoint = NormalizedEndpoint.parse("https://changed.test/v1"),
                model = original.model,
                headersJson = "{}",
                credentialRequired = false,
            ),
            null,
        )
    }

    private suspend fun assertConfigurationConflict(action: suspend () -> Unit) {
        try {
            action()
            org.junit.Assert.fail("Stale model operation must reject its original configuration")
        } catch (_: ProviderDraftSaveConflictException) {
            // Only the explicit configuration conflict satisfies this contract.
        }
    }

    internal class Fixture(
        private val managed: Boolean = false,
        private val draftCatalog: Boolean = false,
    ) : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "provider-models-$suffix")
        val database = "provider-models-$suffix.db"
        val storage = HelixStorage.open(context, database, directory)
        val status = ProviderTestStatusStore(InMemoryLineStore())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val probed = mutableListOf<String>()
        var generations = 0
        var catalog: ModelCatalogResult = ModelCatalogResult.Listed(listOf("a", "b", "new"))
        var catalogCalls = 0
        var contextCalls = 0
        var contextWindow: Long? = null
        var metadataCalls = 0
        var catalogPause: suspend () -> Unit = {}
        val requests = mutableListOf<ModelRequest>()
        val catalogRequests = java.util.concurrent.CopyOnWriteArrayList<WireRequest>()
        var generationFailure: ModelErrorCode? = null
        var capabilityFailure: ModelErrorCode? = null
        var login = ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_IN, FIRST_LOGIN)
        var probePause: suspend () -> Unit = {}
        val service: ProviderService

        init {
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id = "p",
                    displayName = "Fixture",
                    protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    endpoint = "https://example.test/v1",
                    model = "a",
                    headersJson = "{}",
                    secretAlias = null,
                    capabilitySnapshot = ProviderCapabilities.toJsonString(CAPS.copy(toolCalls = false)),
                    authKind = if (managed) "MANAGED_ACCOUNT" else "NONE",
                    provisioningKind = if (managed) "MANAGED_ACCOUNT" else "USER_CONFIGURED",
                ),
            )
            val wire =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        check(draftCatalog && request.method == "GET" && request.url.endsWith("/models")) {
                            "Unexpected fixture request"
                        }
                        catalogRequests += request
                        return WireResponse(
                            200,
                            emptyMap(),
                            object : com.helix.provider.api.wire.WireBody {
                                override suspend fun bytes() =
                                    """{"data":[{"id":"a"},{"id":"b"},{"id":"new"}]}""".toByteArray()

                                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                    error("No streamed fixture request")
                                }

                                override fun close() = Unit
                            },
                        )
                    }
                }
            val factory =
                ProviderFactory(
                    CredentialLookup { error("No credentials") },
                    wire,
                    { error("No images") },
                    additionalFactory = { config -> model(config) },
                )
            val hooks =
                ManagedProviderHooks(
                    isManaged = { managed && it == "p" },
                    accounts = { mapOf("p" to login) },
                    probe = { config, _ ->
                        probed += config.model
                        probePause()
                        capabilityFailure?.let { ProbeOutcome.Failed(3, it, "fixture", false) }
                            ?: ProbeOutcome.Ok(CAPS, listOf(config.model))
                    },
                )
            service =
                ProviderService(
                    storage = storage,
                    factory = factory,
                    testStatus = status,
                    idGenerator = { UUID.randomUUID().toString() },
                    scope = scope,
                    managed = hooks,
                )
        }

        private fun model(config: ProviderConfig) =
            object : ModelProvider {
                override val descriptor =
                    ProviderDescriptor(
                        id = config.id,
                        displayName = config.displayName,
                        connection = config.connection,
                        model = config.model,
                    )

                override suspend fun listModels(): ModelCatalogResult {
                    catalogCalls++
                    catalogPause()
                    return catalog
                }

                override suspend fun contextWindow(model: String): Long? {
                    contextCalls++
                    return contextWindow
                }

                override suspend fun modelMetadata(): Map<String, com.helix.provider.api.ModelMetadata> {
                    metadataCalls++
                    return emptyMap()
                }

                override suspend fun validateConfiguration(): ProviderCheckResult = ProviderCheckResult.Ok

                override fun stream(request: ModelRequest): Flow<ModelEvent> {
                    generations++
                    requests += request
                    return generationFailure?.let { flowOf(ModelEvent.Error(it, false)) }
                        ?: flowOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop"))
                }
            }

        override fun close() {
            scope.cancel()
            storage.close()
            context.deleteDatabase(database)
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val FIRST_LOGIN = "00000000-0000-0000-0000-000000000001"
        const val SECOND_LOGIN = "00000000-0000-0000-0000-000000000002"
        val CAPS =
            ProviderCapabilities(
                streaming = true,
                toolCalls = true,
                parallelToolCalls = false,
                vision = false,
                reasoning = false,
                jsonSchemaOutput = false,
                maxContextTokens = 8192,
                source = CapabilitySource.PROBED,
            )
    }
}
