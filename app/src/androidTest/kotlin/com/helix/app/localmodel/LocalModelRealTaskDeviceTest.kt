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

/** Opt-in downloaded-weight evaluation; scoped synthetic files, production loop and durable evidence. */
class LocalModelRealTaskDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    @Test
    @Suppress("LongMethod") // Complete production turn with fixture setup and cleanup in the same ownership scope.
    fun realModelCompletesFileWorkflow() =
        runBlocking {
            org.junit.Assume.assumeTrue(
                androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
                    .getString("realModel") == "true",
            )
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val storage = container.storage
            val id = "local-loop-${UUID.randomUUID()}"
            val arguments =
                androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
            val taskTimeoutMs = arguments.getString("taskTimeoutSeconds", "900").toLong() * 1000
            require(taskTimeoutMs in 60000..1800000)
            val hash =
                arguments.getString(
                    "modelSha",
                    "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
                )
            val asset = ModelAssetRef(hash, hash, arguments.getString("modelSize", "396705472").toLong())
            val contextTokens =
                androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
                    .getString("contextTokens", "32768")
                    .toInt()
            val client =
                LocalInferenceRuntimeClient(
                    app,
                    com.helix.provider.api.local
                        .ModelAssetStore(java.io.File(app.filesDir, "models")),
                )
            val evidence = java.io.File(app.filesDir, "hxa222-evidence").apply { mkdirs() }
            val job = SupervisorJob()
            try {
                val runtime =
                    object : LocalInferenceRuntimePort by client {
                        override fun generate(request: LocalGenerationRequest) =
                            flow {
                                val callStart = android.os.SystemClock.elapsedRealtime()
                                java.io
                                    .File(
                                        evidence,
                                        "events.txt",
                                    ).appendText("START ${request.generationId} context=$contextTokens\n")
                                // Synthetic fixture only, stored in ignored host evidence after the owned run.
                                java.io.File(evidence, "requests.txt").appendText("${request.request}\n")
                                java.io
                                    .File(evidence, "request-${request.generationId}.json")
                                    .writeBytes(LocalRuntimeCodec.encode(request.request))
                                client.generate(request).collect {
                                    java.io
                                        .File(
                                            evidence,
                                            "events.txt",
                                        ).appendText("${android.os.SystemClock.elapsedRealtime() - callStart}ms $it\n")
                                    emit(it)
                                }
                            }
                    }
                val capabilities =
                    ProviderCapabilities(
                        true,
                        true,
                        false,
                        false,
                        false,
                        false,
                        contextTokens.toLong(),
                        CapabilitySource.MANUAL,
                    )
                val started = android.os.SystemClock.elapsedRealtime()
                val loaded = runtime.load(LocalModelLoadRequest(asset, contextTokens, 2))
                assertEquals(contextTokens.toLong(), runtime.inspect(asset).metadata.contextWindow)
                assertEquals(loaded.handle, runtime.load(loaded.request).handle)
                java.io
                    .File(
                        evidence,
                        "load-ms.txt",
                    ).writeText((android.os.SystemClock.elapsedRealtime() - started).toString())
                val loading = LocalModelLoadRequest(asset, contextTokens, 2)
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
                        "Qwen real task fixture",
                        null,
                        null,
                        asset.id,
                        "{}",
                        null,
                        ProviderCapabilities.toJsonString(capabilities),
                        provisioningKind = "ON_DEVICE_ASSET",
                        transportKind = "ON_DEVICE_LOCAL",
                        authKind = "NONE",
                    ),
                )
                val probe = providers.runCapabilityTest(id)
                java.io.File(evidence, "probe.txt").writeText(probe.toString())
                assertTrue("Real capability probe failed: $probe", probe is com.helix.provider.api.ProbeOutcome.Ok)
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
                val session = chat.createSession("Local real task", id, asset.id)
                val ref =
                    com.helix.core.workspace.FileScopePath.fromModelReference(
                        storage.sessions.resolve(session).directoryRef!!,
                    )
                val directory =
                    storage.workspaces
                        .managedDirectory(ref.scopeId)
                        .resolve(ref.relativePath)
                        .toFile()
                directory.mkdirs()
                java.io.File(directory, "orders.csv").writeText("region,amount\nEast,120\nWest,80\nEast,30\nWest,40\n")
                java.io.File(directory, "returns.csv").writeText("region,amount\nEast,10\nWest,20\n")
                storage.sessionPermissionConfigs.setForSession(
                    session,
                    com.helix.core.policy.SessionPermissionConfig.of(
                        com.helix.core.model.SessionPermissionMode.FULL_ACCESS,
                    ),
                    System.currentTimeMillis(),
                )
                container.toolPipeline.registry.all().filter { it.name.value !in setOf("read", "write") }.forEach {
                    storage.toolAvailability.set(
                        it.origin.canonicalOf(),
                        it.name.value,
                        com.helix.core.model.ToolAvailabilityScope.SESSION,
                        session,
                        com.helix.core.model.ToolAvailabilityState.DISABLED,
                        System.currentTimeMillis(),
                    )
                }
                try {
                    chat.openSession(session)
                    withTimeout(10000) { while (chat.screen.value.openSessionId != session) delay(20) }
                    chat.setMode(AgentMode.ACT)
                    withTimeout(10000) {
                        while (storage.sessionRunControls.forSession(session)?.mode != AgentMode.ACT) delay(20)
                    }
                    chat.setReasoning(com.helix.core.model.ReasoningEffort.OFF)
                    chat.setTurnBudgets(
                        com.helix.core.model
                            .TurnBudgets(12, 12, 32000, 2048, 200000),
                    )
                    delay(500)
                    val receipt =
                        chat
                            .sendSubmission(
                                ChatSubmission(
                                    session,
                                    0,
                                    UUID.randomUUID().toString(),
                                    """
                                    Complete this file task using tools, one step at a time. /no_think
                                    Read orders.csv and returns.csv in the current session directory: ${ref.toModelReference()}
                                    Sum every data row of orders and returns by region, including repeated regions.
                                    Do not use only the first row for a region. Calculate net = orders minus returns.
                                    Write totals.csv with columns region,orders,returns,net and one row per region (East then West).
                                    Write report.md showing each region's addition, totals and grand net total.
                                    Finally read both output files back with the read tool to verify them, then give a brief final answer.
                                    Use only read and write tools; do not merely describe the steps. Do not ask for confirmation.
                                    """.trimIndent(),
                                    emptyList(),
                                ),
                            ).await()
                    assertTrue(receipt.outcome is ChatSubmissionOutcome.Accepted)
                    withTimeout(taskTimeoutMs) {
                        while (storage.turns
                                .listBySession(session)
                                .singleOrNull()
                                ?.state !in
                            setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED", "NEEDS_REVIEW")
                        ) {
                            delay(500)
                        }
                    }
                    val turn = storage.turns.listBySession(session).single()
                    val calls = storage.toolCalls.listByTurn(turn.id)
                    assertEquals("COMPLETED", turn.state)
                    assertTrue("Must execute at least six real tool calls: $calls", calls.size >= 6)
                    assertTrue(calls.all { it.state == "COMPLETED" })
                    for (name in listOf("totals.csv", "report.md")) {
                        val lastWrite = calls.indexOfLast { it.name == "write" && it.argsJson.contains(":$name\"") }
                        assertTrue("Missing write for $name", lastWrite >= 0)
                        assertTrue(
                            "Missing read after final write for $name",
                            calls.drop(lastWrite + 1).any { it.name == "read" && it.argsJson.contains(":$name\"") },
                        )
                    }
                    val totals =
                        java.io
                            .File(directory, "totals.csv")
                            .readLines()
                            .filter { it.isNotBlank() }
                            .map { row -> row.split(',').map { it.trim().trim('"') } }
                    assertEquals(3, totals.size)
                    assertEquals(listOf("region", "orders", "returns", "net"), totals.first().map { it.lowercase() })
                    assertEquals("East", totals[1].first())
                    assertEquals("West", totals[2].first())
                    assertEquals(listOf(150.0, 10.0, 140.0), totals[1].drop(1).map { it.toDouble() })
                    assertEquals(listOf(120.0, 20.0, 100.0), totals[2].drop(1).map { it.toDouble() })
                    assertTrue(
                        java.io
                            .File(directory, "report.md")
                            .readText()
                            .contains("240"),
                    )
                } finally {
                    val turns = storage.turns.listBySession(session)
                    java.io.File(evidence, "trajectory.txt").writeText(
                        "ElapsedMs=${android.os.SystemClock.elapsedRealtime() - started}\n" +
                            turns.joinToString("\n") + "\n" +
                            turns.flatMap { storage.modelCalls.listByTurn(it.id) }.joinToString("\n") + "\n" +
                            turns.flatMap { storage.toolCalls.listByTurn(it.id) }.joinToString("\n"),
                    )
                    for (name in listOf("totals.csv", "report.md")) {
                        java.io
                            .File(directory, name)
                            .takeIf { it.isFile }
                            ?.copyTo(java.io.File(evidence, name), overwrite = true)
                    }
                    java.io.File(evidence, "final-durable.txt").writeText(
                        turns.joinToString("\n") + "\n" +
                            turns.flatMap { storage.toolCalls.listByTurn(it.id) }.joinToString("\n"),
                    )
                    chat.stop()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    providers.delete(id)
                }
            } finally {
                try {
                    client.terminate()
                } finally {
                    job.cancelAndJoin()
                    if (storage.providerConfigs.list().any { it.id == id }) storage.providerConfigs.delete(id)
                }
            }
        }
}
