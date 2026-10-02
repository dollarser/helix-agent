package com.helix.app.localmodel

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTestStatusStore
import com.helix.core.model.AgentMode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ModelMetadata
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.local.LoadedLocalModel
import com.helix.provider.api.local.LocalCancelResult
import com.helix.provider.api.local.LocalGenerationRequest
import com.helix.provider.api.local.LocalInferenceRuntimePort
import com.helix.provider.api.local.LocalModelInspection
import com.helix.provider.api.local.LocalModelLoadRequest
import com.helix.provider.api.local.LocalModelProvider
import com.helix.provider.api.local.LocalRuntimeStatus
import com.helix.provider.api.local.LocalUnloadResult
import com.helix.provider.api.local.ModelAssetRef
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Actual ChatService/AgentLoop/Dispatcher/Room with a local fake runtime; no HTTP or downloaded weights. */
class LocalProviderLoopDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    @Test
    @Suppress("LongMethod") // Complete production turn with fixture setup and cleanup in the same ownership scope.
    fun localToolCallUsesProductionLoopAndDurableBackfill() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val storage = container.storage
            val id = "local-loop-${UUID.randomUUID()}"
            val asset = ModelAssetRef(id, "a".repeat(64), 8)
            val runtime = LoopRuntime()
            val loading = LocalModelLoadRequest(asset, 32768, 2)
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.IO)
            val lines = InMemoryLineStore()
            val status = ProviderTestStatusStore(lines)
            val providers =
                ProviderService(
                    storage,
                    ProviderFactory(
                        credentials = { error("Local inference requested credentials") },
                        wire =
                            object : WireClient {
                                override suspend fun open(request: WireRequest): WireResponse {
                                    error("Unexpected network")
                                }
                            },
                        imageSource = { error("Unexpected image") },
                        additionalFactory = {
                            LocalModelProvider(it, asset, runtime, loading)
                        },
                    ),
                    status,
                    idGenerator = { id },
                    scope = scope,
                )
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    id,
                    "Local loop fixture",
                    null,
                    null,
                    id,
                    "{}",
                    null,
                    ProviderCapabilities.toJsonString(runtime.capabilities),
                    provisioningKind = "ON_DEVICE_ASSET",
                    transportKind = "ON_DEVICE_LOCAL",
                    authKind = "NONE",
                ),
            )
            status.recordPassed(id, System.currentTimeMillis(), runtime.capabilities)
            status.selectedModels.write(id, listOf(id))
            val chat =
                ChatService(
                    storage = storage,
                    providerService = providers,
                    profileStore = container.profileStore,
                    toolPipeline = container.toolPipeline,
                    idGenerator = { UUID.randomUUID().toString() },
                    scope = scope,
                    attachmentStaging =
                        com.helix.app.chat.AttachmentStagingSupport(
                            com.helix.feature.files
                                .AttachmentImporter(container.featureFiles.importPipeline),
                            "app",
                            { error("No attachment fixture") },
                            { error("No attachment fixture") },
                        ),
                    strings = { resource, args -> app.getString(resource, *args) },
                )
            val session = chat.createSession("Local fixture", id, id)
            try {
                chat.openSession(session)
                withTimeout(10000) { while (chat.screen.value.openSessionId != session) delay(20) }
                chat.setMode(AgentMode.ACT)
                withTimeout(10000) {
                    while (storage.sessionRunControls.forSession(session)?.mode != AgentMode.ACT) delay(20)
                }
                com.helix.app.test
                    .discoverFixtureTool(container.toolPipeline, session, "time.now")
                val receipt =
                    chat
                        .sendSubmission(
                            ChatSubmission(
                                session,
                                0,
                                UUID.randomUUID().toString(),
                                "What is the current time?",
                                emptyList(),
                            ),
                        ).await()
                assertTrue(
                    "Expected admission, got ${receipt.outcome}",
                    receipt.outcome is ChatSubmissionOutcome.Accepted ||
                        receipt.outcome is ChatSubmissionOutcome.Enqueued,
                )
                withTimeout(20000) {
                    while (storage.turns
                            .listBySession(session)
                            .singleOrNull()
                            ?.state != "COMPLETED"
                    ) {
                        delay(20)
                    }
                }
                val turn = storage.turns.listBySession(session).single()
                assertEquals(turn.id, storage.sessionInputs.get(receipt.submission.clientRequestId)?.consumedTurnId)
                assertEquals(listOf("COMPLETED"), storage.toolCalls.listByTurn(turn.id).map { it.state })
                assertEquals(listOf("time.now"), storage.toolCalls.listByTurn(turn.id).map { it.name })
                assertEquals(2, storage.modelCalls.listByTurn(turn.id).size)
                assertTrue(runtime.sawBackfill)
            } finally {
                chat.stop()
                storage.sessions.archive(session, System.currentTimeMillis())
                providers.delete(id)
                job.cancelAndJoin()
            }
        }

    private class LoopRuntime : LocalInferenceRuntimePort {
        val capabilities = ProviderCapabilities(true, true, false, false, false, false, 32768, CapabilitySource.PROBED)
        var sawBackfill = false

        override suspend fun inspect(asset: ModelAssetRef) =
            LocalModelInspection(asset, ModelMetadata(contextWindow = 32768), capabilities)

        override suspend fun load(request: LocalModelLoadRequest) = LoadedLocalModel("fixture", request)

        override fun generate(request: LocalGenerationRequest) =
            flow {
                val result = request.request.messages.lastOrNull { it.role == ModelRole.TOOL }
                if (result == null) {
                    emit(ModelEvent.ToolCallStarted(0, ToolCallId("local-time"), "time.now"))
                    emit(ModelEvent.ToolArgumentsDelta(0, "{}"))
                    emit(ModelEvent.ToolCallFinished(0))
                    emit(ModelEvent.Completed("tool_calls"))
                } else {
                    check(result.toolCallId == ToolCallId("local-time") && result.text.isNotBlank())
                    sawBackfill = true
                    emit(ModelEvent.TextDelta("Time checked."))
                    emit(ModelEvent.Completed("stop"))
                }
            }

        override suspend fun cancel(generationId: String) = LocalCancelResult.EXITED

        override suspend fun unload(modelHandle: String) = LocalUnloadResult.UNLOADED

        override suspend fun runtimeStatus() = LocalRuntimeStatus(null, null)

        override suspend fun terminate() = Unit
    }
}
