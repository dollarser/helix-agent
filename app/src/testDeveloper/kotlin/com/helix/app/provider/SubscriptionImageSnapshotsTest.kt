package com.helix.app.provider

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.ImageReselectionRequiredException
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.provider.api.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionImageSnapshotsTest {
    private val config =
        ProviderConfig(
            "p",
            "Fixture",
            ProviderProtocol.OPENAI_RESPONSES,
            NormalizedEndpoint.parse("https://example.com/v1"),
            "vision",
            emptyMap(),
            SecretAlias(ProviderFactory.NO_KEY_ALIAS),
            "{}",
        )
    private val image =
        ImageReference(
            ArtifactRef("image"),
            "image/png",
            ImageBinding("s", "m", "a".repeat(64), "t", "vision", "consent"),
        )
    private val pixels = Base64.getEncoder().encodeToString(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0))

    @Test fun resolvesExactRequestBindingAndProviderForEverySnapshot() {
        val seen = mutableListOf<ImageReference>()
        val source =
            object : VisionImageSource {
                override fun load(
                    image: ImageReference,
                    config: ProviderConfig,
                ): LoadedImage {
                    assertEquals(this@SubscriptionImageSnapshotsTest.config, config)
                    seen += image
                    return LoadedImage("image/png", pixels)
                }
            }
        val otherMessage = image.copy(binding = requireNotNull(image.binding).copy(messageId = "other"))
        val request = ModelRequest("vision", listOf(tool(image), tool(otherMessage)))
        val snapshots = subscriptionImageSnapshots(request, config) { source }
        assertEquals(listOf(image, otherMessage), seen)
        assertEquals(seen, snapshots.map { it.reference })
    }

    @Test fun rejectedBindingStopsSnapshotCreation() {
        val source =
            object : VisionImageSource {
                override fun load(
                    image: ImageReference,
                    config: ProviderConfig,
                ): LoadedImage = throw IllegalArgumentException("Disclosure revoked")
            }
        assertThrows(IllegalArgumentException::class.java) {
            subscriptionImageSnapshots(ModelRequest("vision", listOf(tool(image))), config) { source }
        }
    }

    @Test fun imageReselectionRemainsTypedBeforeCrossingTheRuntimeBoundary() {
        val failure = ImageReselectionRequiredException()
        var loads = 0
        val source =
            VisionImageSource { actualImage, destination ->
                assertEquals(image, actualImage)
                assertEquals(config, destination)
                loads++
                throw failure
            }
        val actual =
            assertThrows(ImageReselectionRequiredException::class.java) {
                subscriptionImageSnapshots(ModelRequest("vision", listOf(tool(image))), config) { source }
            }
        assertSame(failure, actual)
        assertEquals(ModelErrorCode.IMAGE_RESELECT_REQUIRED.name, actual.message)
        assertEquals(1, loads)
    }

    @Test fun textOnlyRequestDoesNotNeedOrLoadAnImageSource() {
        val request = ModelRequest("vision", listOf(ModelMessage(ModelRole.USER, "hello")))
        assertTrue(subscriptionImageSnapshots(request, config, null).isEmpty())
    }

    private fun tool(image: ImageReference) =
        ModelMessage(ModelRole.TOOL, "prepared", listOf(image), ToolCallId("c"), ToolName("view_image"))
}
