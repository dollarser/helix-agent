package com.helix.app.vision

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ArtifactRef
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.NoCancellation
import com.helix.core.model.VisualArtifact
import com.helix.core.policy.AutomationSessionScope
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MobileScreenInheritanceTest {
    private val now = Instant.ofEpochMilli(1000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val lines = InMemoryLineStore()
    private var scope: AutomationSessionScope? =
        AutomationSessionScope(emptySet(), emptySet(), 0, Instant.MAX, true, "grant")

    private fun screens() = MobileUseScreenConsent(clock, lines) { scope }

    private val capturedHash = "c".repeat(64)
    private val original = VisualArtifact("captured", "a".repeat(64), "image/png", 10, 1, 1)
    private val derived = VisualArtifact("derived", "b".repeat(64), "image/jpeg", 10, 1, 1)
    private val call =
        ExecutableToolCall(
            "capture",
            "ui.screenshot",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            now.plusSeconds(60),
            NoCancellation,
            "session",
            "turn",
        )

    private fun derivedImage() =
        ImageReference(
            ArtifactRef(derived.artifactId),
            derived.mediaType,
            ImageBinding("session", "message", derived.sha256, "next-turn", "model"),
        )

    @Test fun exactCapturedBytesCanInheritAcrossTurnsAndRestartWithoutUsingFilenames() {
        screens().register(call, original, scope!!.toScopeRef(), capturedHash)
        val restarted = screens()
        restarted.inherit(call.copy(toolName = "view_image", turnId = "next-turn"), capturedHash, "image/png", derived)
        assertTrue(restarted.isScreenImage(derivedImage()))
        assertTrue(screens().isScreenImage(derivedImage()))
    }

    @Test fun changedBytesMediaSessionOrToolCannotInherit() {
        screens().register(call, original, scope!!.toScopeRef(), capturedHash)
        val view = call.copy(toolName = "view_image", turnId = "next-turn")
        screens().inherit(view, "d".repeat(64), "image/png", derived)
        screens().inherit(view, capturedHash, "image/jpeg", derived)
        screens().inherit(view.copy(sessionId = "other"), capturedHash, "image/png", derived)
        screens().inherit(view.copy(toolName = "browser.screenshot"), capturedHash, "image/png", derived)
        assertFalse(screens().isScreenImage(derivedImage()))
    }

    @Test fun revokedExpiredOrReplacedScopesCannotRelabelAnOldCapture() {
        val originalScope = scope!!
        screens().register(call, original, originalScope.toScopeRef(), capturedHash)
        for (invalid in listOf(null, originalScope.copy(expiresAt = now), originalScope.copy(grantId = "new"))) {
            scope = invalid
            screens().inherit(
                call.copy(toolName = "view_image", turnId = "next-turn"),
                capturedHash,
                "image/png",
                derived,
            )
            assertFalse(screens().isScreenImage(derivedImage()))
        }
    }

    @Test fun normalizedCaptureCanAlsoBeReopenedButAnUnregisteredHashCannot() {
        val view = call.copy(toolName = "view_image", turnId = "next-turn")
        screens().inherit(view, original.sha256, original.mediaType, derived)
        assertFalse(screens().isScreenImage(derivedImage()))
        screens().register(call, original, scope!!.toScopeRef(), capturedHash)
        screens().inherit(view, original.sha256, original.mediaType, derived)
        assertTrue(screens().isScreenImage(derivedImage()))
    }
}
