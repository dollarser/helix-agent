package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRequestTest {
    private val png = ImageReference(ArtifactRef("art.png1"), "image/png")
    private val user = ModelMessage(ModelRole.USER, "hello")

    @Test
    fun minimalRequestParses() {
        val request = ModelRequest(model = "model-test", messages = listOf(user))
        assertEquals("model-test", request.model)
        assertEquals(ReasoningEffort.OFF, request.reasoning)
        assertEquals(1, request.messages.size)
        assertEquals(emptyList<ModelToolSchema>(), request.tools)
    }

    @Test
    fun userMessagesMayCarryImages() {
        val message = ModelMessage(ModelRole.USER, "what is in this image?", images = listOf(png))
        assertEquals(listOf(png), message.images)
        val webp = ImageReference(ArtifactRef("art.w1"), "image/webp")
        val gif = ImageReference(ArtifactRef("art.g1"), "image/gif")
        val jpeg = ImageReference(ArtifactRef("art.j1"), "image/jpeg")
        ModelMessage(ModelRole.USER, "four", images = listOf(png, webp, gif, jpeg))
    }

    @Test
    fun imageRulesAreEnforced() {
        assertThrows<IllegalArgumentException>("non-user image must be rejected") {
            ModelMessage(ModelRole.ASSISTANT, "text", images = listOf(png))
        }
        assertThrows<IllegalArgumentException>("tool result image must be rejected") {
            ModelMessage(
                ModelRole.TOOL,
                "result",
                images = listOf(png),
                toolCallId = ToolCallId("call-1"),
                toolName = ToolName("read"),
            )
        }
        assertThrows<IllegalArgumentException>("unsupported media type must be rejected") {
            ImageReference(ArtifactRef("art.svg1"), "image/svg+xml")
        }
        assertThrows<IllegalArgumentException>("too many images must be rejected") {
            ModelMessage(
                ModelRole.USER,
                "many",
                images = (1..5).map { ImageReference(ArtifactRef("art.m$it"), "image/png") },
            )
        }
    }

    @Test
    fun toolResultMessagesRequireCallIdentity() {
        val ok =
            ModelMessage(
                ModelRole.TOOL,
                "result text",
                toolCallId = ToolCallId("call-1"),
                toolName = ToolName("read"),
            )
        assertEquals(ToolCallId("call-1"), ok.toolCallId)
        assertThrows<IllegalArgumentException>("tool result without call id must be rejected") {
            ModelMessage(ModelRole.TOOL, "result", toolName = ToolName("read"))
        }
        assertThrows<IllegalArgumentException>("tool result without tool name must be rejected") {
            ModelMessage(ModelRole.TOOL, "result", toolCallId = ToolCallId("call-1"))
        }
    }

    @Test
    fun nonToolMessagesCannotCarryCallIdentity() {
        listOf(ModelRole.SYSTEM, ModelRole.USER, ModelRole.ASSISTANT).forEach { role ->
            assertThrows<IllegalArgumentException>("$role with call identity must be rejected") {
                ModelMessage(
                    role,
                    "text",
                    toolCallId = ToolCallId("call-1"),
                    toolName = ToolName("read"),
                )
            }
        }
    }

    @Test
    fun messageTextRulesAreEnforced() {
        assertThrows<IllegalArgumentException>("blank text must be rejected") {
            ModelMessage(ModelRole.USER, "   ")
        }
        assertThrows<IllegalArgumentException>("NUL text must be rejected") {
            ModelMessage(ModelRole.USER, "a\u0000b")
        }
        assertThrows<IllegalArgumentException>("oversize text must be rejected") {
            ModelMessage(ModelRole.USER, "x".repeat(ModelMessage.MAX_TEXT_LENGTH + 1))
        }
        // Newlines/tabs are legitimate text (code blocks).
        assertEquals("a\nb\tc", ModelMessage(ModelRole.USER, "a\nb\tc").text)
    }

    @Test
    fun requestMustEndOnUserOrToolMessage() {
        assertThrows<IllegalArgumentException>("assistant-final request must be rejected") {
            ModelRequest(
                model = "m",
                messages = listOf(user, ModelMessage(ModelRole.ASSISTANT, "answer")),
            )
        }
        assertThrows<IllegalArgumentException>("system-final request must be rejected") {
            ModelRequest(
                model = "m",
                messages =
                    listOf(
                        user,
                        ModelMessage(ModelRole.ASSISTANT, "answer"),
                        ModelMessage(ModelRole.SYSTEM, "late system"),
                    ),
            )
        }
        // Tool-final is legal (the next model call after a tool result).
        val toolFinal =
            ModelRequest(
                model = "m",
                messages =
                    listOf(
                        user,
                        ModelMessage(ModelRole.ASSISTANT, "answer"),
                        ModelMessage(
                            ModelRole.TOOL,
                            "result",
                            toolCallId = ToolCallId("call-1"),
                            toolName = ToolName("read"),
                        ),
                    ),
            )
        assertEquals(ModelRole.TOOL, toolFinal.messages.last().role)
    }

    @Test
    fun messageCountIsBounded() {
        assertThrows<IllegalArgumentException>("empty request must be rejected") {
            ModelRequest(model = "m", messages = emptyList())
        }
        assertThrows<IllegalArgumentException>("oversize request must be rejected") {
            ModelRequest(
                model = "m",
                messages = (1..ModelRequest.MAX_MESSAGES + 1).map { ModelMessage(ModelRole.USER, "m$it") },
            )
        }
        assertEquals(
            ModelRequest.MAX_MESSAGES,
            ModelRequest(
                model = "m",
                messages = (1..ModelRequest.MAX_MESSAGES).map { ModelMessage(ModelRole.USER, "m$it") },
            ).messages.size,
        )
    }

    @Test
    fun toolTableRulesAreEnforced() {
        val schema = ModelToolSchema(ToolName("read"), "read a file", "{}")
        assertThrows<IllegalArgumentException>("duplicate tool name must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), tools = listOf(schema, schema))
        }
        assertThrows<IllegalArgumentException>("too many tools must be rejected") {
            ModelRequest(
                model = "m",
                messages = listOf(user),
                tools =
                    (1..ModelRequest.MAX_TOOLS + 1)
                        .map { ModelToolSchema(ToolName("tool$it"), "t", "{}") },
            )
        }
        assertEquals(
            ModelRequest.MAX_TOOLS,
            ModelRequest(
                model = "m",
                messages = listOf(user),
                tools = (1..ModelRequest.MAX_TOOLS).map { ModelToolSchema(ToolName("tool$it"), "t", "{}") },
            ).tools.size,
        )
    }

    @Test
    fun toolSchemaMustBeAJsonObject() {
        listOf("[1,2]", "\"obj\"", "not json", "1", "{} with garbage").forEach { json ->
            assertThrows<IllegalArgumentException>("bad schema json must be rejected: ${json.take(12)}") {
                ModelToolSchema(ToolName("read"), "read", json)
            }
        }
        val nested = """{"type":"object","properties":{"path":{"type":"string"}}}"""
        assertEquals(nested, ModelToolSchema(ToolName("read"), "read", nested).inputSchemaJson)
        assertThrows<IllegalArgumentException>("blank schema must be rejected") {
            ModelToolSchema(ToolName("read"), "read", "  ")
        }
        assertThrows<IllegalArgumentException>("oversize schema must be rejected") {
            ModelToolSchema(
                ToolName("read"),
                "read",
                "{" + (1..257).joinToString(",") { "\"k$it\":1" } + "}",
            )
        }
    }

    @Test
    fun toolSchemaDescriptionRulesAreEnforced() {
        assertThrows<IllegalArgumentException>("blank description must be rejected") {
            ModelToolSchema(ToolName("read"), "  ", "{}")
        }
        assertThrows<IllegalArgumentException>("control char description must be rejected") {
            ModelToolSchema(ToolName("read"), "a\u0001b", "{}")
        }
        assertThrows<IllegalArgumentException>("oversize description must be rejected") {
            ModelToolSchema(ToolName("read"), "x".repeat(ModelToolSchema.MAX_DESCRIPTION_LENGTH + 1), "{}")
        }
    }

    @Test
    fun samplingParametersAreValidated() {
        assertThrows<IllegalArgumentException>("NaN temperature must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), temperature = Double.NaN)
        }
        assertThrows<IllegalArgumentException>("infinite temperature must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), temperature = Double.POSITIVE_INFINITY)
        }
        assertThrows<IllegalArgumentException>("temperature above 2.0 must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), temperature = 2.1)
        }
        assertThrows<IllegalArgumentException>("negative temperature must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), temperature = -0.1)
        }
        assertEquals(2.0, ModelRequest(model = "m", messages = listOf(user), temperature = 2.0).temperature)
        assertEquals(0.0, ModelRequest(model = "m", messages = listOf(user), temperature = 0.0).temperature)
        assertThrows<IllegalArgumentException>("maxOutputTokens 0 must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), maxOutputTokens = 0)
        }
        assertThrows<IllegalArgumentException>("negative maxOutputTokens must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), maxOutputTokens = -1)
        }
        assertEquals(1L, ModelRequest(model = "m", messages = listOf(user), maxOutputTokens = 1).maxOutputTokens)
    }

    @Test
    fun stopSequencesAreBounded() {
        assertThrows<IllegalArgumentException>("too many stop sequences must be rejected") {
            ModelRequest(
                model = "m",
                messages = listOf(user),
                stopSequences = (1..5).map { "s$it" },
            )
        }
        assertThrows<IllegalArgumentException>("blank stop sequence must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), stopSequences = listOf("  "))
        }
        assertThrows<IllegalArgumentException>("oversize stop sequence must be rejected") {
            ModelRequest(
                model = "m",
                messages = listOf(user),
                stopSequences = listOf("x".repeat(ModelRequest.MAX_STOP_SEQUENCE_LENGTH + 1)),
            )
        }
        assertThrows<IllegalArgumentException>("control char stop sequence must be rejected") {
            ModelRequest(model = "m", messages = listOf(user), stopSequences = listOf("a\u0001b"))
        }
        assertEquals(
            4,
            ModelRequest(
                model = "m",
                messages = listOf(user),
                stopSequences = listOf("a", "b", "c", "d"),
            ).stopSequences.size,
        )
    }

    @Test
    fun modelIdRulesAreEnforced() {
        assertThrows<IllegalArgumentException>("blank model must be rejected") {
            ModelRequest(model = "  ", messages = listOf(user))
        }
        assertThrows<IllegalArgumentException>("oversize model must be rejected") {
            ModelRequest(model = "x".repeat(ModelRequest.MAX_MODEL_LENGTH + 1), messages = listOf(user))
        }
        assertThrows<IllegalArgumentException>("whitespace model must be rejected") {
            ModelRequest(model = "a b", messages = listOf(user))
        }
        assertThrows<IllegalArgumentException>("control char model must be rejected") {
            ModelRequest(model = "a\u0001b", messages = listOf(user))
        }
    }
}
