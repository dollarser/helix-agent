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
                        cleartext = null,
                        templateNotes = emptyList(),
                    )
                f.service.update("p", draft, null, false)
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
                check(f.service.runConnectionTest("p", "b") is ProbeOutcome.Ok)
                check(f.service.runCapabilityTest("p", "b") is ProbeOutcome.Ok)
                f.generationFailure = ModelErrorCode.PROTOCOL
                check(f.service.runConnectionTest("p", "a") is ProbeOutcome.Failed)
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
                check(f.service.runConnectionTest("p", "b") is ProbeOutcome.Ok)
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

    internal class Fixture(
        private val managed: Boolean = false,
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
        val requests = mutableListOf<ModelRequest>()
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
                        error("No network")
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
                    bindings = CleartextBindingStore(InMemoryLineStore()),
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

                override suspend fun listModels(): ModelCatalogResult =
                    ModelCatalogResult.Listed(listOf("a", "b", "new"))

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
