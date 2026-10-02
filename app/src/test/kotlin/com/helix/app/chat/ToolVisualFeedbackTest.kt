package com.helix.app.chat

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.vision.ToolVisionConsent
import com.helix.core.model.ArtifactRef
import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.Clock
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderTransport
import com.helix.core.model.SecretAlias
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.core.storage.dao.InteractionReceiptDao
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.storage.entity.InteractionReceiptEntity
import com.helix.core.storage.entity.MessageAttachmentEntity
import com.helix.core.storage.repository.InteractionReceiptRepository
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/** Exercises the actual history projector with trusted-store fixtures, not a separate reimplementation. */
class ToolVisualFeedbackTest {
    @Test fun actualProjectorAddsPixelsOnlyAfterExplicitConsent() =
        runBlocking {
            val fixture = Fixture()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { fixture.restore() }
            assertFalse(pending.isCompleted)
            fixture.consent.respond(
                fixture.consent.pending.value
                    .single()
                    .id,
                true,
            )
            val projected = pending.await()
            assertEquals(ModelRole.TOOL, projected.single().role)
            assertEquals(1, projected.single().images.size)
            assertTrue(
                projected
                    .single()
                    .images
                    .single()
                    .binding!!
                    .consentId != null,
            )
            assertTrue(
                fixture.messages
                    .single()
                    .images
                    .isEmpty(),
            )
            assertEquals(projected, fixture.restore())
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
        }

    @Test fun rejectedDisclosurePreservesReceiptButExplicitlyOmitsPixels() =
        runBlocking {
            val fixture = Fixture()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { fixture.restore() }
            fixture.consent.respond(
                fixture.consent.pending.value
                    .single()
                    .id,
                false,
            )
            val message = pending.await().single()
            assertTrue(message.images.isEmpty())
            assertTrue(message.modelText.contains("PIXELS_NOT_SENT"))
            assertEquals(fixture.messages.single().text, message.text)
            assertEquals(fixture.messages.single(), message.withoutVisualProjection())
            assertEquals("call", message.toolCallId!!.value)
            assertTrue(
                fixture
                    .restore()
                    .single()
                    .images
                    .isEmpty(),
            )
        }

    @Test fun unsupportedModelAndOldTurnNeverLoadOrDiscloseImageBytes() =
        runBlocking {
            val fixture = Fixture()
            fixture.vision = false
            assertTrue(
                fixture
                    .restore()
                    .single()
                    .modelText
                    .contains("VISION_UNAVAILABLE"),
            )
            fixture.vision = true
            assertTrue(
                fixture
                    .restore("successor")
                    .single()
                    .modelText
                    .contains("OUTSIDE_RECENT_WINDOW"),
            )
            assertEquals(0, fixture.imageReads)
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
        }

    @Test fun arbitraryToolJsonCannotCreateAnImageWithoutTrustedAttachmentRelation() =
        runBlocking {
            val fixture = Fixture()
            fixture.relationPresent = false
            assertEquals(fixture.messages, fixture.restore())
            assertEquals(0, fixture.imageReads)
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
        }

    @Test fun destinationChangeWhilePromptIsOpenPreventsVisualProjection() =
        runBlocking {
            val fixture = Fixture()
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.restore() }
                }
            fixture.destination = fixture.config("https://changed.example/v1")
            fixture.consent.respond(
                fixture.consent.pending.value
                    .single()
                    .id,
                true,
            )
            assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
        }

    @Test fun corruptVisualFactsFailBeforeAnyPrompt() =
        runBlocking {
            val fixture = Fixture()
            fixture.artifact = fixture.artifact.copy(sha256 = "b".repeat(64))
            assertTrue(runCatching { fixture.restore() }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
        }

    @Test fun onDeviceVisionReceivesBoundPixelsWithoutNetworkDisclosure() =
        runBlocking {
            val fixture = Fixture()
            val local = fixture.localConfig()
            fixture.destination = local
            val message = fixture.restore(config = local).single()
            assertEquals(1, message.images.size)
            assertEquals(
                null,
                message.images
                    .single()
                    .binding!!
                    .consentId,
            )
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
            assertEquals(1, fixture.imageReads)
        }

    @Test fun loopbackHttpIsNotTreatedAsDirectOnDeviceInference() =
        runBlocking {
            val fixture = Fixture()
            val network = fixture.config("http://127.0.0.1:11434/v1")
            fixture.destination = network
            val pending = async(start = CoroutineStart.UNDISPATCHED) { fixture.restore(config = network) }
            assertFalse(pending.isCompleted)
            fixture.consent.respond(
                fixture.consent.pending.value
                    .single()
                    .id,
                false,
            )
            assertTrue(
                pending
                    .await()
                    .single()
                    .images
                    .isEmpty(),
            )
        }

    @Test fun localDestinationDriftStillFailsBeforeImageProjection() =
        runBlocking {
            val fixture = Fixture()
            val local = fixture.localConfig()
            assertTrue(runCatching { fixture.restore(config = local) }.exceptionOrNull() is IllegalStateException)
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
        }

    @Test fun mobileStartConsentFeedsPixelsAndStopsOnGrantLoss() =
        runBlocking {
            val fixture = Fixture()
            fixture.messages = fixture.messages.map { it.copy(toolName = ToolName("ui.screenshot")) }
            fixture.registerNativeScreen()
            val first = fixture.restore().single()
            assertEquals(1, first.images.size)
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
            assertEquals(first, fixture.restore().single())
            fixture.phoneScope = null
            assertTrue(
                fixture
                    .restore()
                    .single()
                    .images
                    .isEmpty(),
            )
            assertFalse(
                fixture.consent.granted(
                    first.images.single(),
                    fixture.config(),
                    first.images
                        .single()
                        .binding!!
                        .consentId!!,
                ),
            )
        }

    @Test fun localMobilePixelsKeepGrantProvenanceWithoutNetworkPrompt() =
        runBlocking {
            val fixture = Fixture()
            fixture.messages = fixture.messages.map { it.copy(toolName = ToolName("ui.screenshot")) }
            fixture.destination = fixture.localConfig()
            fixture.registerNativeScreen()
            val local = fixture.restore(config = fixture.destination).single()
            assertEquals(1, local.images.size)
            assertTrue(
                fixture.consent.pending.value
                    .isEmpty(),
            )
            assertTrue(
                fixture.consent.granted(
                    local.images.single(),
                    fixture.destination,
                    local.images
                        .single()
                        .binding!!
                        .consentId!!,
                ),
            )
            fixture.phoneScope = null
            assertTrue(
                fixture
                    .restore(config = fixture.destination)
                    .single()
                    .images
                    .isEmpty(),
            )
        }

    private class Fixture {
        private val hash = "a".repeat(64)
        var artifact = ArtifactEntity("image", "s", "scope:app:output/image.png", "image/png", 70, hash, "turn")
        var relationPresent = true
        var vision = true
        var imageReads = 0
        var phoneScope: com.helix.core.policy.AutomationSessionScope? =
            com.helix.core.policy
                .AutomationSessionScope(emptySet(), emptySet(), 0, Instant.MAX, true, "grant")
        private val clock =
            object : Clock {
                override fun now(): Instant = Instant.ofEpochMilli(1000)
            }
        val consent =
            ToolVisionConsent(
                InteractionReceiptRepository(receiptDao()),
                clock,
                com.helix.app.vision
                    .MobileUseScreenConsent(clock) { phoneScope },
            )

        fun config(endpoint: String = "https://fixture.example/v1") =
            ProviderConfig(
                "p",
                "Fixture",
                ProviderProtocol.OPENAI_RESPONSES,
                NormalizedEndpoint.parse(endpoint),
                "vision",
                emptyMap(),
                SecretAlias("fixture"),
                "{}",
            )

        fun localConfig() =
            ProviderConfig(
                "p",
                "On-device",
                ProviderConnection(
                    ProviderProvisioningKind.ON_DEVICE_ASSET,
                    ProviderTransport.OnDeviceLocal,
                    ProviderAuth.None,
                ),
                "vision",
                emptyMap(),
                "{}",
            )

        var destination = config()
        private val json = """{"id":"call","tool":"view_image","status":"SUCCEEDED","summary":"prepared",
          "visualArtifact":{"artifactId":"image","sha256":"$hash","mediaType":"image/png",
          "sizeBytes":70,"width":1,"height":1}}"""
        private val rows = listOf(ChatHistoryBuilder.PersistedRow("turn", "TOOL", "TOOL_RESULT", json, "message"))
        var messages =
            listOf(
                ModelMessage(
                    ModelRole.TOOL,
                    "prepared",
                    toolCallId = ToolCallId("call"),
                    toolName = ToolName("view_image"),
                ),
            )
        private val projector =
            ToolVisualFeedback(
                attachmentsFor = { id ->
                    if (relationPresent) {
                        listOf(
                            MessageAttachmentEntity(
                                1,
                                id,
                                "image",
                                0,
                                AttachmentPurpose.TOOL_OBSERVATION,
                                hash,
                            ),
                        )
                    } else {
                        emptyList()
                    }
                },
                artifactFor = { artifact },
                visionAvailable = {
                    _,
                    _,
                    ->
                    vision
                },
                configFor = { destination },
                consent = consent,
            )

        suspend fun restore(
            turn: String = "turn",
            config: ProviderConfig = config(),
        ) = projector.restore(
            "s",
            turn,
            config,
            "vision",
            messages,
            rows,
        ) { id ->
            imageReads++
            listOf(ImageReference(ArtifactRef("image"), "image/png", ImageBinding("s", id, hash)))
        }

        fun registerNativeScreen() {
            val call =
                com.helix.tools.framework.ExecutableToolCall(
                    "call",
                    "ui.screenshot",
                    "1",
                    kotlinx.serialization.json.JsonObject(emptyMap()),
                    com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                    Instant.MAX,
                    com.helix.tools.framework.NoCancellation,
                    "s",
                    "turn",
                )
            consent.mobileScreens.register(
                call,
                com.helix.core.model
                    .VisualArtifact("image", hash, "image/png", 70, 1, 1),
                phoneScope!!.toScopeRef(),
            )
        }

        private fun receiptDao(): InteractionReceiptDao {
            val rows = mutableMapOf<String, InteractionReceiptEntity>()
            return Proxy.newProxyInstance(
                InteractionReceiptDao::class.java.classLoader,
                arrayOf(InteractionReceiptDao::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "byId" -> {
                        rows[args[0]]
                    }

                    "insert" -> {
                        val row = args[0] as InteractionReceiptEntity
                        rows[row.id] = row
                        Unit
                    }

                    "supersedeOlder" -> {
                        0
                    }

                    "answer" -> {
                        val row = rows[args[0]]!!
                        check(row.state == "PENDING" && row.expiresAt > args[3] as Long)
                        rows[row.id] =
                            row.copy(
                                state = "ANSWERED",
                                answerHash = args[1] as String,
                                answeredAt = args[2] as Long,
                            )
                        1
                    }

                    "cancel" -> {
                        val row = rows[args[0]]!!
                        if (row.state == "PENDING") {
                            rows[row.id] = row.copy(state = "CANCELLED")
                            1
                        } else {
                            0
                        }
                    }

                    else -> {
                        error("Unexpected receipt operation ${method.name}")
                    }
                }
            } as InteractionReceiptDao
        }
    }
}
