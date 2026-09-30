package com.helix.runtime.cli.client

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolImageOmission
import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/** IPC fixtures validate reference/byte transport, not image decoding or remote recognition. */
class CliToolImageCodecTest {
    private val bytes = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)
    private val base64 = Base64.getEncoder().encodeToString(bytes)

    @Test fun antigravityImagesRoundTripWithoutBecomingCodex() {
        val original = image("google-tool")
        val image = original.copy(binding = requireNotNull(original.binding).copy(modelId = "google-model"))
        val request = ModelRequest("google-model", listOf(tool(listOf(image))))
        val encoded =
            CliModelRequestCodec.encode(
                request,
                CliModelProvider.ANTIGRAVITY,
                listOf(CliImageSnapshot(image, base64)),
            )
        val decoded = CliModelRequestCodec.decodeEnvelope(encoded)
        assertEquals(CliModelProvider.ANTIGRAVITY, decoded.provider)
        assertEquals("google-model", decoded.request.model)
        assertEquals(base64, decoded.images.single().base64)
        val forged = encoded.decodeToString().replace("\"providerId\":\"antigravity\"", "\"providerId\":\"grok\"")
        assertThrows(IllegalArgumentException::class.java) { CliModelRequestCodec.decodeEnvelope(forged.toByteArray()) }
    }

    @Test fun antigravityPublicStateCarriesNoCredentialAndAcceptsTheNewProvider() {
        val states = mapOf("antigravity" to CliAccountState("LOGGED_OUT"))
        assertEquals(states, CliAccountState.decode(CliAccountState.encode(states)))
    }

    @Test fun toolPixelsKeepRoleAndCallAcrossRuntimeBoundary() {
        val image = image("tool-message")
        val message = tool(listOf(image))
        val encoded =
            CliModelRequestCodec.encode(
                ModelRequest("vision", listOf(message)),
                images = listOf(CliImageSnapshot(image, base64)),
            )
        val decoded = CliModelRequestCodec.decodeEnvelope(encoded)
        assertEquals(
            ModelRole.TOOL,
            decoded.request.messages
                .single()
                .role,
        )
        assertEquals(
            message.toolCallId,
            decoded.request.messages
                .single()
                .toolCallId,
        )
        assertEquals(
            image.copy(binding = null),
            decoded.request.messages
                .single()
                .images
                .single(),
        )
        assertEquals(base64, decoded.images.single().base64)
        assertFalse(encoded.decodeToString().contains("consentId"))
    }

    @Test fun distinctApprovedMessagesSharingOneArtifactUseOneImmutableWireSnapshot() {
        val first = image("first")
        val second = image("second")
        val request = ModelRequest("vision", listOf(tool(listOf(first)), tool(listOf(second), "next-call")))
        val decoded =
            CliModelRequestCodec.decodeEnvelope(
                CliModelRequestCodec.encode(
                    request,
                    images = listOf(CliImageSnapshot(first, base64), CliImageSnapshot(second, base64)),
                ),
            )
        assertEquals(1, decoded.images.size)
        assertEquals(2, decoded.request.messages.size)
        assertEquals(
            decoded.request.messages
                .first()
                .images,
            decoded.request.messages
                .last()
                .images,
        )
    }

    @Test fun conflictingBytesCannotHideBehindTransportDeduplication() {
        val first = image("first")
        val second = image("second")
        val changed = Base64.getEncoder().encodeToString(bytes.copyOf().also { it[it.lastIndex] = 1 })
        assertThrows(IllegalArgumentException::class.java) {
            CliModelRequestCodec.encode(
                ModelRequest("vision", listOf(tool(listOf(first)), tool(listOf(second)))),
                images = listOf(CliImageSnapshot(first, base64), CliImageSnapshot(second, changed)),
            )
        }
    }

    @Test fun omittedPixelsRemainAnExplicitWireNoticeWithoutChangingCanonicalInput() {
        val message = tool(emptyList()).copy(imageOmission = ToolImageOmission.VISION_UNAVAILABLE)
        val request = ModelRequest("text-only", listOf(message))
        val decoded = CliModelRequestCodec.decode(CliModelRequestCodec.encode(request))
        assertEquals(message.modelText, decoded.messages.single().text)
        assertEquals("prepared", message.text)
        assertEquals(emptyList<ImageReference>(), decoded.messages.single().images)
    }

    private fun image(message: String) =
        ImageReference(
            ArtifactRef("image"),
            "image/png",
            ImageBinding("s", message, "a".repeat(64), "t", "vision", "consent"),
        )

    private fun tool(
        images: List<ImageReference>,
        call: String = "call",
    ) = ModelMessage(ModelRole.TOOL, "prepared", images, ToolCallId(call), ToolName("view_image"))
}
