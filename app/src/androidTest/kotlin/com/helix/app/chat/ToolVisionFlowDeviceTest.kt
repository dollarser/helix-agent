package com.helix.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.helix.app.ToolArtifactRegistrationSink
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.provider.ArtifactVisionImageSource
import com.helix.app.vision.BoundImageAccess
import com.helix.app.vision.ToolImagePreparer
import com.helix.app.vision.ToolVisionConsent
import com.helix.core.agent.SettledCall
import com.helix.core.model.ArtifactRef
import com.helix.core.model.BoundToolResult
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderTransport
import com.helix.core.model.SecretAlias
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.resolveFileScopePath
import com.helix.provider.api.ProviderConfig
import com.helix.provider.openai.responses.ImagePayload
import com.helix.provider.openai.responses.ImageResolver
import com.helix.provider.openai.responses.ResponsesRequestEncoder
import com.helix.tools.files.ViewImageTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Offline pixel, storage and encoding integration; no model service is invoked. */
class ToolVisionFlowDeviceTest {
    @Test
    fun pixelsFlowThroughHistoryAndConsent() =
        runBlocking {
            Fixture().use { fixture ->
                val result = fixture.prepare() as ToolExecutorResult.Completed
                fixture.coordinator.settleBatchCall("call", false)
                fixture.coordinator.openNextModelCall(listOf(fixture.draft(result)), "next")
                assertUnapprovedPixelsBlocked(fixture)
                val messages = authorizeProjection(fixture)
                val pixels = fixture.source.load(messages.last().images.single(), fixture.config)
                assertPixelContent(pixels.base64)
                val body =
                    ResponsesRequestEncoder(
                        ImageResolver { image ->
                            ImagePayload.Base64(fixture.source.load(image, fixture.config).base64)
                        },
                    ).encode(ModelRequest("vision", messages))
                assertTrue(body.contains("input_image"))
                assertTrue(body.contains(pixels.base64))
                assertEquals(
                    1,
                    fixture.storage.messages
                        .listBySession("s")
                        .count { it.role == "USER" },
                )
            }
        }

    @Test
    fun localToolPixelsNeedNoNetworkConsentAndCannotBeReusedForNetwork(): Unit =
        runBlocking {
            Fixture(onDevice = true).use { fixture ->
                val result = fixture.prepare() as ToolExecutorResult.Completed
                fixture.coordinator.settleBatchCall("call", false)
                fixture.coordinator.openNextModelCall(listOf(fixture.draft(result)), "next")
                val rows = fixture.history()
                val projected =
                    fixture.feedback.restore(
                        "s",
                        "turn",
                        fixture.config,
                        "vision",
                        ChatHistoryBuilder.toModelMessages(rows),
                        rows,
                    ) { id -> listOf(fixture.image(id)) }
                val image = projected.last().images.single()
                assertEquals(null, image.binding!!.consentId)
                assertTrue(
                    fixture.consent.pending.value
                        .isEmpty(),
                )
                assertPixelContent(fixture.source.load(image, fixture.config).base64)
                val remote =
                    ProviderConfig(
                        "p",
                        "Remote",
                        ProviderProtocol.OPENAI_RESPONSES,
                        NormalizedEndpoint.parse("https://fixture.example/v1"),
                        "vision",
                        emptyMap(),
                        SecretAlias("fixture"),
                        "{}",
                    )
                assertThrows(IllegalArgumentException::class.java) { fixture.source.load(image, remote) }
            }
        }

    @Test fun mobileGrantIsRecheckedAtTheFinalPixelReadForNetworkAndLocal() =
        runBlocking {
            for (local in listOf(false, true)) {
                Fixture(onDevice = local).use { fixture ->
                    val result = fixture.prepare() as ToolExecutorResult.Completed
                    fixture.coordinator.settleBatchCall("call", false)
                    fixture.coordinator.openNextModelCall(listOf(fixture.draft(result)), "next")
                    // Trusted captured-image fixture: no real Accessibility capture or network call.
                    fixture.consent.mobileScreens.register(
                        fixture.call.copy(toolName = "ui.screenshot"),
                        requireNotNull(result.visualArtifact),
                        fixture.phoneScope!!.toScopeRef(),
                    )
                    val row =
                        fixture.storage.messages
                            .listBySession("s")
                            .single { it.role == "TOOL" }
                    val source = fixture.image(row.id)
                    val candidate = source.copy(binding = source.binding!!.copy(turnId = "turn", modelId = "vision"))
                    val receipt = fixture.consent.requestMobileScreen(candidate, fixture.config)
                    assertTrue(receipt != null)
                    val bound = candidate.copy(binding = candidate.binding!!.copy(consentId = receipt))
                    assertPixelContent(fixture.source.load(bound, fixture.config).base64)
                    assertTrue(
                        fixture.consent.pending.value
                            .isEmpty(),
                    )
                    fixture.phoneScope = null
                    assertThrows(IllegalArgumentException::class.java) { fixture.source.load(bound, fixture.config) }
                }
            }
        }

    private fun assertUnapprovedPixelsBlocked(fixture: Fixture) {
        val row =
            fixture.storage.messages
                .listBySession("s")
                .single { it.role == "TOOL" }
        val binding =
            fixture.storage.messageAttachments
                .listByMessage(row.id)
                .single()
        assertEquals("TOOL_OBSERVATION", binding.purpose)
        assertFalse(
            fixture.storage.messages
                .readContent(row)!!
                .contains("base64"),
        )
        val unapproved =
            fixture.image(row.id).copy(
                binding = ImageBinding("s", row.id, binding.boundSha256, "turn", "vision"),
            )
        assertThrows(IllegalArgumentException::class.java) { fixture.source.load(unapproved, fixture.config) }
    }

    private suspend fun authorizeProjection(fixture: Fixture) =
        kotlinx.coroutines.coroutineScope {
            val rows = fixture.history()
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.feedback.restore(
                        "s",
                        "turn",
                        fixture.config,
                        "vision",
                        ChatHistoryBuilder.toModelMessages(rows),
                        rows,
                    ) { id -> listOf(fixture.image(id)) }
                }
            assertFalse(pending.isCompleted)
            fixture.consent.respond(
                fixture.consent.pending.value
                    .single()
                    .id,
                true,
            )
            pending.await()
        }

    private fun assertPixelContent(base64: String) {
        val bytes = Base64.getDecoder().decode(base64)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertEquals(Color.RED, bitmap.getPixel(2, 8))
            assertEquals(Color.GREEN, bitmap.getPixel(13, 8))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun invalidBindingRollsBackHistory() {
        Fixture().use { fixture ->
            val result = fixture.prepare() as ToolExecutorResult.Completed
            val draft = fixture.draft(result)
            val invalid = draft.copy(visualArtifact = result.visualArtifact!!.copy(sha256 = "b".repeat(64)))
            fixture.coordinator.settleBatchCall("call", false)
            val before = fixture.storage.messages.listBySession("s")
            assertThrows(IllegalStateException::class.java) {
                fixture.coordinator.openNextModelCall(listOf(invalid), "next")
            }
            assertEquals(before, fixture.storage.messages.listBySession("s"))
            assertThrows(IllegalArgumentException::class.java) { fixture.storage.modelCalls.resolve("next") }
        }
    }

    @Test
    fun invalidSourcesDoNotPublishImages() {
        Fixture().use { fixture ->
            fixture.input.writeText("not an image")
            assertTrue(fixture.prepare() is ToolExecutorResult.Failed)
            assertTrue(
                fixture.storage.artifacts
                    .listBySession("s")
                    .isEmpty(),
            )
            assertThrows(IllegalArgumentException::class.java) {
                fixture.preparer.prepare(fixture.call, "scope:ws:input/sample.png", "a".repeat(64))
            }
        }
    }

    private class Fixture(
        onDevice: Boolean = false,
    ) : Closeable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val name = "vision-${UUID.randomUUID()}.db"
        private val root = File(context.cacheDir, name).apply { mkdirs() }
        val storage = HelixStorage.open(context, name, File(root, "content"))
        private val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(1000)
            }
        val config =
            if (onDevice) {
                ProviderConfig(
                    "p",
                    "Fixture",
                    ProviderConnection(
                        ProviderProvisioningKind.ON_DEVICE_ASSET,
                        ProviderTransport.OnDeviceLocal,
                        ProviderAuth.None,
                    ),
                    "vision",
                    emptyMap(),
                    "{}",
                )
            } else {
                ProviderConfig(
                    "p",
                    "Fixture",
                    ProviderProtocol.OPENAI_RESPONSES,
                    NormalizedEndpoint.parse("https://fixture.example/v1"),
                    "vision",
                    emptyMap(),
                    SecretAlias("fixture"),
                    "{}",
                )
            }
        private val roots =
            ScopeRootResolver { scope ->
                require(scope == "ws")
                File(root, "workspace").toPath()
            }
        private val workspace = WorkspaceArtifactStore(roots).apply { ensureLayout("ws") }
        val input = File(root, "workspace/input/sample.png")
        val coordinator: TurnCoordinator
        var phoneScope: com.helix.core.policy.AutomationSessionScope? =
            com.helix.core.policy
                .AutomationSessionScope(emptySet(), emptySet(), 0, Instant.MAX, true, "fixture-grant")
        val consent =
            ToolVisionConsent(
                storage.interactionReceipts,
                clock,
                com.helix.app.vision
                    .MobileUseScreenConsent(clock) { phoneScope },
            )
        val source =
            ArtifactVisionImageSource(storage.artifacts, workspace) { image, destination ->
                BoundImageAccess(storage, consent) { _, _ -> true }.verify(image, destination)
            }
        val feedback =
            ToolVisualFeedback(
                storage.messageAttachments::listByMessage,
                storage.artifacts::resolve,
                { _, _ -> true },
                { config },
                consent,
            )
        val preparer =
            ToolImagePreparer(
                storage,
                workspace,
                ToolArtifactRegistrationSink(storage, workspace::openRead) {
                    resolveFileScopePath(it, roots).toFile()
                },
                "ws",
                File(root, "staging"),
                clock,
                { true },
            )
        val call =
            ExecutableToolCall(
                "call",
                "view_image",
                "1",
                JsonObject(mapOf("path" to JsonPrimitive("scope:ws:input/sample.png"))),
                ExecutionTargetType.LOCAL_ANDROID,
                clock.now().plusSeconds(30),
                NoCancellation,
                "s",
                "turn",
            )

        init {
            storage.providerConfigs.save(
                ProviderConfigSpec(
                    "p",
                    "Fixture",
                    if (onDevice) null else ProviderProtocol.OPENAI_RESPONSES,
                    if (onDevice) null else "https://fixture.example/v1",
                    "vision",
                    "{}",
                    if (onDevice) null else "fixture",
                    "{}",
                    provisioningKind = if (onDevice) "ON_DEVICE_ASSET" else "USER_CONFIGURED",
                    transportKind = if (onDevice) "ON_DEVICE_LOCAL" else "NETWORK",
                    authKind = if (onDevice) "NONE" else "SECRET",
                ),
            )
            storage.sessions.create("s", "Vision fixture", "p", "vision", 1000)
            coordinator =
                TurnCoordinator.start(
                    storage,
                    clock,
                    { UUID.randomUUID().toString() },
                    TurnStartSpec("s", "turn", "first", "fixture", "Inspect the image"),
                )
            val stream = coordinator.beginModelStream()
            stream.apply(ModelEvent.ToolCallStarted(0, ToolCallId("call"), "view_image"))
            stream.apply(ModelEvent.ToolArgumentsDelta(0, call.args.toString()))
            stream.apply(ModelEvent.ToolCallFinished(0))
            coordinator.beginToolBatch(listOf("call"))
            val toolStep = """[{"id":"call","name":"view_image","arguments":"{}"}]"""
            coordinator.commitModelToolStep(toolStep)
            createPixels()
        }

        fun prepare() = ViewImageTool.executor(preparer).execute(call)

        fun draft(result: ToolExecutorResult.Completed) =
            ChatToolMessageEncoder { _, _ -> "" }.toolResultDraft(
                SettledCall(
                    "call",
                    "view_image",
                    ToolDispatchOutcome.Succeeded(
                        BoundToolResult(
                            result.output.toString(),
                            Sha256("a".repeat(64)),
                            false,
                            1,
                            result.visualArtifact,
                        ),
                    ),
                ),
            )

        fun image(messageId: String): ImageReference {
            val binding = storage.messageAttachments.listByMessage(messageId).single()
            val artifact = storage.artifacts.resolve(binding.artifactId)
            return ImageReference(
                ArtifactRef(artifact.id),
                artifact.mediaType,
                ImageBinding("s", messageId, binding.boundSha256),
            )
        }

        fun history() =
            storage.messages.listBySession("s").map { row ->
                ChatHistoryBuilder.PersistedRow(
                    row.turnId,
                    row.role,
                    row.kind,
                    storage.messages.readContent(row),
                    row.id,
                )
            }

        private fun createPixels() {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            for (x in 0 until 16) {
                for (y in 0 until 16) {
                    bitmap.setPixel(x, y, if (x < 8) Color.RED else Color.GREEN)
                }
            }
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }

        override fun close() {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
