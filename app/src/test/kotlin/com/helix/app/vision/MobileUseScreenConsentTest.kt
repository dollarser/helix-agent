package com.helix.app.vision

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ArtifactRef
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.model.VisualArtifact
import com.helix.core.policy.AutomationSessionScope
import com.helix.provider.api.ProviderConfig
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MobileUseScreenConsentTest {
    private var now = Instant.ofEpochMilli(1000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val sources = InMemoryLineStore()
    private var scope: AutomationSessionScope? =
        AutomationSessionScope(emptySet(), emptySet(), 0, Instant.MAX, true, "grant-1")

    private fun consent() = MobileUseScreenConsent(clock, sources) { session -> scope.takeIf { session == "s" } }

    private val screens = consent()
    private val config = config()

    private fun config(endpoint: String = "https://example.com/v1") =
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

    private fun image(
        name: String = "one",
        turn: String = "turn",
        session: String = "s",
    ) = ImageReference(
        ArtifactRef(name),
        "image/png",
        ImageBinding(session, "message-$name", "a".repeat(64), turn, "vision"),
    )

    private fun register(
        image: ImageReference,
        sourceScope: String = scope!!.toScopeRef(),
    ) {
        val binding = image.binding!!
        val call =
            ExecutableToolCall(
                "call",
                "ui.screenshot",
                "1",
                JsonObject(emptyMap()),
                ExecutionTargetType.LOCAL_ANDROID,
                now.plusSeconds(60),
                NoCancellation,
                binding.sessionId,
                binding.turnId,
            )
        screens.register(call, VisualArtifact(image.ref.value, binding.sha256, image.mediaType, 10, 1, 1), sourceScope)
    }

    @Test fun screenshotsAcrossTurnsUseOnePersistentConfigurationWithoutTimeExpiry() {
        val first = image().also(::register)
        val id = requireNotNull(screens.requestId(first, config))
        now = now.plusSeconds(86400 * 30L)
        assertTrue(screens.granted(first, config, id))
        val next = image("second", "next-turn").also(::register)
        assertTrue(screens.granted(next, config, requireNotNull(screens.requestId(next, config))))
    }

    @Test fun recreationRestoresBothConfigurationAndNativeSourceProof() {
        val first = image().also(::register)
        val id = requireNotNull(screens.requestId(first, config))
        val restarted = consent()
        assertTrue(restarted.isScreenImage(first))
        assertEquals(id, restarted.requestId(first, config))
        assertTrue(restarted.granted(first, config, id))
    }

    @Test fun temporaryRuntimeLossDoesNotMatterButExplicitRevokeDoes() {
        val first = image().also(::register)
        val id = requireNotNull(screens.requestId(first, config))
        // There is deliberately no Accessibility connection or lock-state dependency in this service.
        repeat(3) { assertTrue(consent().granted(first, config, id)) }
        scope = null
        assertNull(consent().requestId(first, config))
        assertFalse(screens.granted(first, config, id))
    }

    @Test fun captureCannotBeRelabelledWithAReplacementOrExpandedGrant() {
        val first = image().also(::register)
        val previousScope = scope!!.toScopeRef()
        val old = requireNotNull(screens.requestId(first, config))
        scope = scope!!.copy(grantId = "grant-2")
        assertFalse(screens.granted(first, config, old))
        assertThrows(IllegalArgumentException::class.java) { register(image("late"), previousScope) }
        val next = image("fresh").also(::register)
        assertTrue(screens.granted(next, config, requireNotNull(screens.requestId(next, config))))
    }

    @Test fun nativeSourceRegistrationIsIdempotentButCannotRewriteAcquisitionProvenance() {
        val first = image().also(::register)
        register(first)
        val id = requireNotNull(screens.requestId(first, config))
        scope = scope!!.copy(grantId = "replacement")
        assertThrows(IllegalStateException::class.java) { register(first) }
        assertFalse(screens.granted(first, config, id))
        assertNull(screens.requestId(first, config))
    }

    @Test fun userModelOrProviderChangesDoNotAskAgainButOldRequestProofCannotChangeRecipient() {
        val first = image().also(::register)
        val old = requireNotNull(screens.requestId(first, config))
        for (changed in listOf(
            config("https://other.example/v1"),
            config.copy(id = "other"),
            config.copy(headers = mapOf("x-route" to "other")),
        )) {
            assertFalse(screens.granted(first, changed, old))
            val newProof = requireNotNull(screens.requestId(first, changed))
            assertTrue(screens.granted(first, changed, newProof))
        }
        val nextModel = first.copy(binding = first.binding!!.copy(modelId = "another-vision-model"))
        val newProof = requireNotNull(screens.requestId(nextModel, config))
        assertNotEquals(old, newProof)
        assertTrue(screens.granted(nextModel, config, newProof))
        assertTrue(screens.granted(first, config, old))
    }

    @Test fun ordinaryArtifactsAndChangedContentOrConversationAreNotScreens() {
        val first = image().also(::register)
        val id = requireNotNull(screens.requestId(first, config))
        val binding = first.binding!!
        for (other in listOf(
            image("ordinary"),
            first.copy(binding = binding.copy(sha256 = "b".repeat(64))),
            first.copy(binding = binding.copy(turnId = "other")),
            first.copy(binding = binding.copy(sessionId = "b")),
        )) {
            assertNull(screens.requestId(other, config))
            assertFalse(screens.granted(other, config, id))
        }
        assertThrows(IllegalArgumentException::class.java) { register(image("foreign", session = "b")) }
    }

    @Test fun missingPersistentSourceNeverFallsBackToAnUnrelatedImageGrant() {
        val first = image().also(::register)
        val empty = MobileUseScreenConsent(clock) { scope }
        assertNull(empty.requestId(first, config))
        assertFalse(empty.isScreenImage(first))
    }

    @Test fun narrowingScopeInvalidatesOlderScreensEvenWithAnUnchangedGrantId() {
        val first = image().also(::register)
        val id = requireNotNull(screens.requestId(first, config))
        scope = scope!!.copy(allApplications = false, allowedPackages = setOf("com.example.app"))
        assertFalse(screens.granted(first, config, id))
    }
}
