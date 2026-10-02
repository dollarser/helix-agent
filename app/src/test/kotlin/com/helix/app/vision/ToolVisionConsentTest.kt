package com.helix.app.vision

import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.ArtifactRef
import com.helix.core.model.Clock
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.storage.dao.InteractionReceiptDao
import com.helix.core.storage.entity.InteractionReceiptEntity
import com.helix.core.storage.repository.InteractionReceiptRepository
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ToolVisionConsentTest {
    private var now = Instant.ofEpochMilli(1000)
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val dao = MemoryReceipts()
    private val consent = ToolVisionConsent(InteractionReceiptRepository(dao), clock)
    private val image =
        ImageReference(
            ArtifactRef("image-a"),
            "image/png",
            ImageBinding("s", "message", "a".repeat(64), "turn", "vision"),
        )

    private fun config(endpoint: String = "https://example.com/v1") =
        ProviderConfig(
            "p",
            "Vision",
            ProviderProtocol.OPENAI_RESPONSES,
            NormalizedEndpoint.parse(endpoint),
            "vision",
            emptyMap(),
            SecretAlias("fixture-key"),
            "{}",
        )

    private fun summary(config: ProviderConfig) =
        (
            EgressDisclosure.decide(
                listOf(EgressDisclosure.OutgoingContent.Image("test image", 128, "a".repeat(64), "image/png", 16, 16)),
                "",
                EgressDisclosure.EgressTarget(
                    config.id,
                    config.displayName,
                    config.protocol,
                    config.endpoint.origin,
                    config.residence(),
                ),
            ) as EgressDisclosure.Decision.Confirm
        ).summary

    @Test fun explicitAllowIsReusableOnlyForTheExactImageAndDestination() =
        runBlocking {
            val config = config()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { consent.request(image, config, summary(config)) }
            val id =
                consent.pending.value
                    .single()
                    .id
            assertFalse(consent.granted(image, config, id))
            consent.respond(id, true)
            assertEquals(id, waiting.await())
            assertTrue(consent.granted(image, config, id))
            assertEquals(id, consent.request(image, config, summary(config)))
            assertTrue(consent.pending.value.isEmpty())
            assertFalse(consent.granted(image, config("https://other.example/v1"), id))
            assertFalse(consent.granted(image.copy(ref = ArtifactRef("other-image")), config, id))
            val binding = requireNotNull(image.binding)
            val otherSession = image.copy(binding = binding.copy(sessionId = "other"))
            val otherHash = image.copy(binding = binding.copy(sha256 = "b".repeat(64)))
            val otherModel = image.copy(binding = binding.copy(modelId = "different"))
            assertFalse(consent.granted(otherSession, config, id))
            assertFalse(consent.granted(otherHash, config, id))
            assertFalse(consent.granted(otherModel, config, id))
        }

    @Test fun denialDoesNotUploadOrAutomaticallyPromptAgain() =
        runBlocking {
            val config = config()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { consent.request(image, config, summary(config)) }
            val id =
                consent.pending.value
                    .single()
                    .id
            consent.respond(id, false)
            assertNull(waiting.await())
            assertFalse(consent.granted(image, config, id))
            assertNull(consent.request(image, config, summary(config)))
            assertTrue(consent.pending.value.isEmpty())
        }

    @Test fun cancellationRemovesPromptAndLateUiAnswerCannotAuthorize() =
        runBlocking {
            val config = config()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { consent.request(image, config, summary(config)) }
            val id =
                consent.pending.value
                    .single()
                    .id
            waiting.cancelAndJoin()
            consent.respond(id, true)
            assertFalse(consent.granted(image, config, id))
            assertEquals("CANCELLED", dao.byId(id)!!.state)
            assertTrue(consent.pending.value.isEmpty())
        }

    @Test fun expiredAndRestartedPendingReceiptsAreNotConsent() =
        runBlocking {
            val config = config()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { consent.request(image, config, summary(config)) }
            val id =
                consent.pending.value
                    .single()
                    .id
            val restarted = ToolVisionConsent(InteractionReceiptRepository(dao), clock)
            assertNull(restarted.request(image, config, summary(config)))
            consent.respond(id, true)
            assertEquals(id, waiting.await())
            now = now.plusSeconds(601)
            assertFalse(consent.granted(image, config, id))
        }

    private var phoneScope: com.helix.core.policy.AutomationSessionScope? =
        com.helix.core.policy
            .AutomationSessionScope(emptySet(), emptySet(), 0, Instant.MAX, true, "phone-grant")

    private fun mobileConsent() =
        ToolVisionConsent(
            InteractionReceiptRepository(dao),
            clock,
            MobileUseScreenConsent(clock) { phoneScope },
        )

    private fun nativeImage(
        consent: ToolVisionConsent,
        name: String = "screen",
        turn: String = "turn",
    ): ImageReference {
        val image = image.copy(ref = ArtifactRef(name), binding = image.binding!!.copy(turnId = turn))
        val call =
            com.helix.tools.framework.ExecutableToolCall(
                "call",
                "ui.screenshot",
                "1",
                kotlinx.serialization.json.JsonObject(emptyMap()),
                com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                now.plusSeconds(60),
                com.helix.tools.framework.NoCancellation,
                "s",
                turn,
            )
        consent.mobileScreens.register(
            call,
            com.helix.core.model
                .VisualArtifact(name, image.binding!!.sha256, "image/png", 128, 16, 16),
            phoneScope!!.toScopeRef(),
        )
        return image
    }

    @Test fun conversationGrantAvoidsScreenDialogsAcrossTurns() =
        runBlocking {
            val mobile = mobileConsent()
            val config = config()
            val first = nativeImage(mobile)
            val firstId = requireNotNull(mobile.requestMobileScreen(first, config))
            now = now.plusSeconds(900)
            val next = nativeImage(mobile, "screen-2", "next-turn")
            val nextId = requireNotNull(mobile.requestMobileScreen(next, config))
            assertTrue(mobile.granted(first, config, firstId))
            assertTrue(mobile.granted(next, config, nextId))
            assertTrue(mobile.pending.value.isEmpty())
            assertTrue(dao.recent("s", 100).isEmpty())
        }

    @Test fun simultaneousNativeScreensNeedNoCardsOrInteractionReceipts() =
        runBlocking {
            val mobile = mobileConsent()
            val config = config()
            val first = nativeImage(mobile)
            val next = nativeImage(mobile, "screen-2")
            val one = async { mobile.requestMobileScreen(first, config) }
            val two = async { mobile.requestMobileScreen(next, config) }
            assertTrue(mobile.granted(first, config, requireNotNull(one.await())))
            assertTrue(mobile.granted(next, config, requireNotNull(two.await())))
            assertTrue(mobile.pending.value.isEmpty())
            assertTrue(dao.recent("s", 100).isEmpty())
        }

    @Test fun explicitConversationRevocationStopsFurtherImageSharingWithoutCreatingCards() =
        runBlocking {
            val mobile = mobileConsent()
            val config = config()
            val first = nativeImage(mobile)
            val next = nativeImage(mobile, "next")
            val firstId = requireNotNull(mobile.requestMobileScreen(first, config))
            phoneScope = null
            assertNull(mobile.requestMobileScreen(next, config))
            assertFalse(mobile.granted(first, config, firstId))
            assertTrue(mobile.pending.value.isEmpty())
        }

    @Test fun replyingToAnOldScreenIdCannotRestoreARevokedGrant() =
        runBlocking {
            val mobile = mobileConsent()
            val config = config()
            val image = nativeImage(mobile)
            val id = requireNotNull(mobile.requestMobileScreen(image, config))
            phoneScope = null
            mobile.respond(id, true)
            assertFalse(mobile.granted(image, config, id))
            assertTrue(mobile.pending.value.isEmpty())
        }

    @Test fun cancellingOneRequestDoesNotChangePersistentScreenPermission() =
        runBlocking {
            val mobile = mobileConsent()
            val config = config()
            val first = nativeImage(mobile)
            val cancelled = async(start = CoroutineStart.LAZY) { mobile.requestMobileScreen(first, config) }
            cancelled.cancelAndJoin()
            val id = requireNotNull(mobile.requestMobileScreen(first, config))
            assertTrue(mobile.granted(first, config, id))
            assertTrue(mobile.pending.value.isEmpty())
        }

    @Test fun nativeLocalAndNetworkScreensReuseTheGrantButHaveDifferentBoundProofs() =
        runBlocking {
            val mobile = mobileConsent()
            val local =
                ProviderConfig(
                    "local",
                    "Device",
                    com.helix.core.model.ProviderConnection(
                        com.helix.core.model.ProviderProvisioningKind.ON_DEVICE_ASSET,
                        com.helix.core.model.ProviderTransport.OnDeviceLocal,
                        com.helix.core.model.ProviderAuth.None,
                    ),
                    "vision",
                    emptyMap(),
                    "{}",
                )
            val first = nativeImage(mobile)
            val localId = requireNotNull(mobile.requestMobileScreen(first, local))
            assertTrue(mobile.granted(first, local, localId))
            val network = config("http://127.0.0.1:11434/v1")
            val networkId = requireNotNull(mobile.requestMobileScreen(first, network))
            assertTrue(mobile.granted(first, network, networkId))
            assertFalse(mobile.granted(first, network, localId))
            assertTrue(mobile.pending.value.isEmpty())
            assertTrue(dao.recent("s", 100).isEmpty())
        }

    @Test fun screenSharingDoesNotAuthorizeOrdinaryImagesOrRelabelOlderCaptures() =
        runBlocking {
            val mobile = mobileConsent()
            val config = config()
            val screen = nativeImage(mobile)
            val old = requireNotNull(mobile.requestMobileScreen(screen, config))
            assertNull(mobile.requestMobileScreen(image, config))
            val ordinary = async(start = CoroutineStart.UNDISPATCHED) { mobile.request(image, config, summary(config)) }
            assertFalse(ordinary.isCompleted)
            mobile.respond(
                mobile.pending.value
                    .single()
                    .id,
                false,
            )
            assertNull(ordinary.await())
            phoneScope = phoneScope!!.copy(grantId = "new-grant")
            assertNull(mobile.requestMobileScreen(screen, config))
            assertFalse(mobile.granted(screen, config, old))
            val fresh = nativeImage(mobile, "fresh")
            assertTrue(mobile.granted(fresh, config, requireNotNull(mobile.requestMobileScreen(fresh, config))))
            assertTrue(mobile.pending.value.isEmpty())
            assertEquals(1, dao.recent("s", 100).size)
        }

    /** In-memory SQL-equivalent receipt transitions; no Android, network or models are invoked. */
    private class MemoryReceipts : InteractionReceiptDao {
        private val rows = linkedMapOf<String, InteractionReceiptEntity>()

        override fun insert(receipt: InteractionReceiptEntity) {
            check(receipt.id !in rows)
            rows[receipt.id] = receipt
        }

        override fun byId(id: String) = rows[id]

        override fun pending(
            sessionId: String,
            now: Long,
        ) = rows.values.filter {
            it.sessionId == sessionId &&
                it.state == "PENDING" &&
                it.expiresAt > now
        }

        override fun recent(
            sessionId: String,
            limit: Int,
        ) = rows.values
            .filter {
                it.sessionId == sessionId
            }.sortedByDescending { it.createdAt }
            .take(limit)

        override fun deleteBySession(sessionId: String): Int {
            val keys = rows.filterValues { it.sessionId == sessionId }.keys.toList()
            keys.forEach(rows::remove)
            return keys.size
        }

        override fun answer(
            id: String,
            answerHash: String,
            answeredAt: Long,
            now: Long,
        ): Int {
            val row = rows[id]?.takeIf { it.state == "PENDING" && it.expiresAt > now } ?: return 0
            rows[id] = row.copy(state = "ANSWERED", answerHash = answerHash, answeredAt = answeredAt)
            return 1
        }

        override fun cancel(id: String): Int {
            val row = rows[id]?.takeIf { it.state == "PENDING" } ?: return 0
            rows[id] = row.copy(state = "CANCELLED")
            return 1
        }

        override fun supersedeOlder(
            requestId: String,
            version: Int,
        ): Int {
            val old = rows.values.filter { it.requestId == requestId && it.version < version && it.state == "PENDING" }
            old.forEach { rows[it.id] = it.copy(state = "SUPERSEDED") }
            return old.size
        }
    }
}
