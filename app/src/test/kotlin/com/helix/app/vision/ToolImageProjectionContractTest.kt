package com.helix.app.vision

import com.helix.app.agent.ContextSegments
import com.helix.app.agent.ModelInputEstimate
import com.helix.app.localmodel.LocalRuntimeCodec
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolImageOmission
import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolImageProjectionContractTest {
    private val original =
        ModelMessage(
            ModelRole.TOOL,
            "prepared",
            toolCallId = ToolCallId("call"),
            toolName = ToolName("view_image"),
        )

    @Test fun typedOmissionKeepsCompactionIdentityButCannotHideChangedHistory() {
        for (reason in ToolImageOmission.entries) {
            val projected = original.copy(imageOmission = reason)
            assertEquals("prepared", projected.text)
            assertTrue(ContextSegments.sameHistoryMessage(projected, original))
            assertFalse(ContextSegments.sameHistoryMessage(projected.copy(text = "forged"), original))
            assertFalse(ContextSegments.sameHistoryMessage(projected.copy(toolCallId = ToolCallId("other")), original))
        }
    }

    @Test fun allEncodersExposeOmissionWithoutResolvingPixelsOrAddingUserIntent() {
        for (reason in ToolImageOmission.entries) {
            val message = original.copy(imageOmission = reason)
            val request = request(message)
            val responses =
                com.helix.provider.openai.responses
                    .ResponsesRequestEncoder(
                        com.helix.provider.openai.responses
                            .ImageResolver { error("no pixels permitted") },
                    ).encode(request)
            val chat =
                com.helix.provider.openai.chat
                    .ChatCompletionsRequestEncoder(
                        com.helix.provider.openai.chat
                            .ImageResolver { error("no pixels permitted") },
                    ).encode(request)
            val anthropic =
                com.helix.provider.anthropic
                    .AnthropicRequestEncoder(
                        com.helix.provider.anthropic
                            .ImageResolver { error("no pixels permitted") },
                    ).encode(request)
            val local = LocalRuntimeCodec.encode(request).toString(Charsets.UTF_8)
            for (body in listOf(responses, chat, anthropic, local)) {
                assertTrue(body.contains(reason.explanation))
                assertTrue(body.contains("Do not claim to have seen these pixels"))
                assertFalse(body.contains("base64"))
            }
            assertEquals(1, request.messages.count { it.role == ModelRole.USER })
            assertEquals(original, message.withoutVisualProjection())
        }
    }

    private fun request(message: ModelMessage): ModelRequest {
        val call =
            com.helix.core.model
                .AssistantToolCall(ToolCallId("call"), ToolName("view_image"), "{}")
        return ModelRequest(
            "vision",
            listOf(
                ModelMessage(ModelRole.USER, "inspect"),
                ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                message,
            ),
        )
    }

    @Test fun admissionEstimateIncludesTheActualObservationNotice() {
        val projection = original.copy(imageOmission = ToolImageOmission.DISCLOSURE_UNAVAILABLE)
        val base = ModelInputEstimate.of(listOf(original), emptyList())
        val actual = ModelInputEstimate.of(listOf(projection), emptyList())
        assertTrue(actual.history > base.history)
        assertEquals(0L, actual.images)
    }
}
