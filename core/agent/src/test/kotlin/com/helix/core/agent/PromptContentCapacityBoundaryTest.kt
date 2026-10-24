package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.ArtifactRef
import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContentCapacityBoundaryTest {
    private val control = RunControlConfig(AgentMode.ACT, true, TurnBudgets(maxOutputTokens = 128))

    private fun request(required: String = "Protected instruction"): TurnContextRequest {
        val prompt =
            PromptRegistry()
                .register(PromptSection("required", 0, PromptScope.SKILL, PromptSource.EXTERNAL_CONTENT) { required })
                .register(
                    PromptSection(
                        "optional",
                        1,
                        PromptScope.SKILL,
                        PromptSource.EXTERNAL_CONTENT,
                        retention = PromptContentRetention.OPTIONAL,
                    ) { "Optional catalogue entry".repeat(160) },
                ).resolveAndAssemble()
        return TurnContextRequest(
            "fixture",
            prompt.modelMessages() + ModelMessage(ModelRole.USER, "Task"),
            emptyList(),
            128,
            ReasoningEffort.OFF,
            prompt,
        )
    }

    @Test fun actualSchemaCostDisplacesOptionalWithoutRemovingSchemaOrProtectedText() {
        val original = request()
        val settings = ProviderContextSettings(manualWindow = original.inputTokens() + 128 + 10, autoCompact = false)
        assertEquals(
            2,
            ContextCompiler
                .projectContent(original, settings)
                .prompt
                ?.sections
                ?.size,
        )
        val schema =
            ModelToolSchema(
                ToolName("fixture"),
                "Description".repeat(20),
                """{"type":"object","properties":{"text":{"type":"string"}}}""",
            )
        val withSchema = original.copy(tools = listOf(schema))
        assertTrue(ModelInputEstimate.of(withSchema.messages, withSchema.tools).tools > 10)
        val projected = ContextCompiler.projectContent(withSchema, settings)
        assertEquals(listOf("required"), projected.prompt?.sections?.map { it.name })
        assertEquals(listOf(schema), projected.tools)
        assertEquals(original.messages.last(), projected.messages.last())
        assertEquals("Protected instruction", projected.messages.first().text)
    }

    @Test fun imageCostDisplacesOptionalWithoutChangingToolHistoryOrImages() {
        val call = AssistantToolCall(ToolCallId("call"), ToolName("fixture"), """{"value":1}""")
        val history =
            listOf(
                ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                ModelMessage(ModelRole.TOOL, "Observed result", toolCallId = call.id, toolName = call.name),
                ModelMessage(
                    ModelRole.USER,
                    "Inspect",
                    images = listOf(ImageReference(ArtifactRef("image"), "image/png")),
                ),
            )
        val original = request().let { it.copy(messages = it.messages.take(1) + history) }
        val withoutImages = original.copy(messages = original.messages.map { it.copy(images = emptyList()) })
        val settings =
            ProviderContextSettings(manualWindow = withoutImages.inputTokens() + 128 + 10, autoCompact = false)
        assertEquals(
            2,
            ContextCompiler
                .projectContent(withoutImages, settings)
                .prompt
                ?.sections
                ?.size,
        )
        val projected = ContextCompiler.projectContent(original, settings)
        assertEquals(listOf("required"), projected.prompt?.sections?.map { it.name })
        assertEquals(history, projected.messages.drop(1))
        assertEquals(2048L, ModelInputEstimate.of(projected.messages, projected.tools).images)
    }

    @Test fun irreducibleRequiredContentFailsAdmissionWithoutProducingWireRequest() =
        runBlocking {
            var reads = 0
            val source = source { reads++ }
            val round =
                ContextCompactionRound(
                    source,
                    ContextSummaryFormat("Summarize", "Facts"),
                    control,
                    ProviderContextSettings(manualWindow = 1024, autoCompact = false),
                    false,
                )
            val prepared = round.prepare(request("Required".repeat(1000)))
            assertTrue(reads > 0)
            assertEquals("CONTEXT_WINDOW_LIMIT", prepared.failure?.errorCode)
            assertNull(prepared.request)
            assertNull(prepared.plan)
            assertEquals(
                listOf("required"),
                prepared.context.prompt
                    ?.sections
                    ?.map { it.name },
            )
        }

    @Test fun cancelledPrepareDoesNotReadSourcesOrProduceRequest() =
        runBlocking {
            var reads = 0
            var cancelled = false
            var returned = false
            val round =
                ContextCompactionRound(
                    source { reads++ },
                    ContextSummaryFormat("Summarize", "Facts"),
                    control,
                    ProviderContextSettings(manualWindow = 1024, autoCompact = false),
                    false,
                )
            launch {
                currentCoroutineContext().cancel()
                try {
                    round.prepare(request())
                    returned = true
                } catch (_: CancellationException) {
                    cancelled = true
                }
            }.join()
            assertTrue(cancelled)
            assertTrue(!returned)
            assertEquals(0, reads)
        }

    private fun source(onRead: () -> Unit): ContextCompactionSource =
        object : ContextCompactionSource {
            override suspend fun inputFloor(model: String): Long {
                onRead()
                return 0L
            }

            override suspend fun plan(
                request: TurnContextRequest,
                control: RunControlConfig,
                settings: ProviderContextSettings,
                force: Boolean,
                inputScale: Double,
            ): ContextCompactionPlan? {
                onRead()
                return null
            }
        }
}
