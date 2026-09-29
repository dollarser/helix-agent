package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolImageContractTest {
    private val image = ImageReference(ArtifactRef("image"), "image/png")

    @Test fun toolObservationCarriesPixelsWithoutBecomingUserInput() {
        val message =
            ModelMessage(ModelRole.TOOL, "prepared", listOf(image), ToolCallId("call"), ToolName("view_image"))
        assertEquals(ModelRole.TOOL, message.role)
        assertEquals(listOf(image), message.images)
        ModelRequest("vision", listOf(message))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pixelsAndOmissionAreMutuallyExclusive() {
        ModelMessage(
            ModelRole.TOOL,
            "prepared",
            listOf(image),
            ToolCallId("c"),
            ToolName("view_image"),
            imageOmission = ToolImageOmission.VISION_UNAVAILABLE,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun userCannotClaimHostDerivedOmission() {
        ModelMessage(ModelRole.USER, "input", imageOmission = ToolImageOmission.VISION_UNAVAILABLE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun systemCannotCarryPixels() {
        ModelMessage(ModelRole.SYSTEM, "policy", listOf(image))
    }

    @Test(expected = IllegalArgumentException::class)
    fun assistantCannotCarryPixels() {
        ModelMessage(ModelRole.ASSISTANT, "answer", listOf(image))
    }

    @Test(expected = IllegalArgumentException::class)
    fun consentCannotBeReusedForDifferentModel() {
        val bound = image.copy(binding = ImageBinding("s", "message", "a".repeat(64), "t", "model-a", "consent"))
        ModelRequest(
            "model-b",
            listOf(ModelMessage(ModelRole.TOOL, "prepared", listOf(bound), ToolCallId("c"), ToolName("view_image"))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun imageCannotExceedTheWireLimit() {
        VisualArtifact("a", "a".repeat(64), "image/png", VisionLimits.MAX_NORMALIZED_RAW_BYTES.toLong() + 1, 1, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun executableContentCannotBeTypedAsVisualArtifact() {
        VisualArtifact("a", "a".repeat(64), "text/html", 10, 1, 1)
    }
}
