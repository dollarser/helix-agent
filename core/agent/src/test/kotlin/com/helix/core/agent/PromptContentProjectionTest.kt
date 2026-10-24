package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContentProjectionTest {
    private val settings = ProviderContextSettings(manualWindow = 1024, autoCompact = false)
    private val control = RunControlConfig(AgentMode.ACT, true, TurnBudgets(maxOutputTokens = 128))

    private fun request(
        optional: String,
        history: String = "U",
        required: String = "R",
    ): TurnContextRequest {
        val snapshot =
            PromptRegistry()
                .register(PromptSection("required", 0, PromptScope.SKILL, PromptSource.EXTERNAL_CONTENT) { required })
                .register(
                    PromptSection(
                        "optional",
                        1,
                        PromptScope.SKILL,
                        PromptSource.EXTERNAL_CONTENT,
                        retention = PromptContentRetention.OPTIONAL,
                        identity = PromptContentIdentity("fixture", "version-a", "resource"),
                    ) { optional },
                ).resolveAndAssemble()
        return TurnContextRequest(
            "fixture",
            snapshot.modelMessages() + ModelMessage(ModelRole.USER, history),
            emptyList(),
            128,
            ReasoningEffort.OFF,
            snapshot,
        )
    }

    @Test fun wholeOmittedContentCanReturnAfterHistoryShrinksWithoutRereading() {
        val input = request("引用😀".repeat(120), "x".repeat(3000))
        val first = ContextCompiler.projectContent(input, settings)
        val omitted = requireNotNull(first.prompt).omittedSections.single()
        assertEquals(PromptOmissionReason.CONTENT_BUDGET, omitted.omissionReason)
        assertEquals("引用😀".repeat(120), omitted.content)
        assertNull(omitted.readReference)
        val shorter = first.copy(messages = first.messages.dropLast(1) + ModelMessage(ModelRole.USER, "U"))
        val restored = ContextCompiler.projectContent(shorter, settings)
        assertTrue(requireNotNull(restored.prompt).sections.any { it.name == "optional" })
        assertTrue(requireNotNull(restored.prompt).omittedSections.isEmpty())
        assertEquals(
            input.prompt
                ?.sections
                ?.last()
                ?.contentHash,
            restored.prompt
                ?.sections
                ?.last()
                ?.contentHash,
        )
        assertEquals(restored.prompt?.content, restored.messages.first().text)
    }

    @Test fun optionalContentCannotItselfReachTheAutoCompactionBoundary() {
        // ceil((1 + 2 + 2628) / 4) + 32 message overhead + ceil(1 / 4) + 128 output = 819.
        // The configured trigger is floor(1024 * 80 / 100) = 819, so equality must be omitted.
        val input = request("C".repeat(2628))
        val automatic = settings.copy(autoCompact = true)
        val projected = ContextCompiler.projectContent(input, automatic)
        assertEquals(listOf("required"), projected.prompt?.sections?.map { it.name })
        assertFalse(ContextCompiler.needsPlan(projected, automatic, false, 0))
        assertTrue(
            ContextCompiler
                .projectContent(input, settings)
                .prompt!!
                .sections
                .any { it.name == "optional" },
        )
    }

    @Test fun calibrationAndOutputReserveUseTheActualCommonWindow() {
        val input = request("C".repeat(2000))
        assertEquals(
            2,
            ContextCompiler
                .projectContent(input, settings)
                .prompt
                ?.sections
                ?.size,
        )
        val calibrated = ContextCompiler.projectContent(input, settings, inputScale = 2.0)
        assertEquals(1, calibrated.prompt?.sections?.size)
        assertEquals(
            TrustLevel.UNTRUSTED,
            calibrated.prompt
                ?.sections
                ?.single()
                ?.trust,
        )
        assertEquals("U", calibrated.messages.last().text)
        val largerReserve = input.copy(maxOutputTokens = 600)
        assertEquals(
            1,
            ContextCompiler
                .projectContent(largerReserve, settings)
                .prompt
                ?.sections
                ?.size,
        )
    }

    @Test fun oversizedProtectedHistoryStillReachesTheExistingCompactionPort() =
        runBlocking {
            var planned = false
            val input = request("optional", "history".repeat(2000))
            val source =
                object : ContextCompactionSource {
                    override suspend fun inputFloor(model: String) = 0L

                    override suspend fun plan(
                        request: TurnContextRequest,
                        control: RunControlConfig,
                        settings: ProviderContextSettings,
                        force: Boolean,
                        inputScale: Double,
                    ): ContextCompactionPlan {
                        planned = true
                        assertEquals(listOf("required"), request.prompt?.sections?.map { it.name })
                        val retainedMessages = request.messages.take(1) + ModelMessage(ModelRole.USER, "U")
                        val retained = request.copy(messages = retainedMessages)
                        return ContextCompactionPlan(
                            1,
                            SummaryOutputBudget(128, 128).modelRequest(
                                "fixture",
                                "old",
                                ContextSummaryFormat("Summarize.", "Facts."),
                            ),
                            retained,
                            originalInputTokens = request.inputTokens(),
                        )
                    }
                }
            val round =
                ContextCompactionRound(
                    source,
                    ContextSummaryFormat("Summarize.", "Facts."),
                    control,
                    settings,
                    false,
                )
            val prepared = round.prepare(input)
            assertTrue(planned)
            assertNull(prepared.failure)
            assertTrue(prepared.plan != null)
        }

    @Test fun snapshotMismatchFailsBeforeARequestIsAdmitted() =
        runBlocking {
            val input =
                request("optional").let {
                    it.copy(messages = listOf(ModelMessage(ModelRole.USER, "drift")))
                }
            val source =
                object : ContextCompactionSource {
                    override suspend fun inputFloor(model: String) = 0L

                    override suspend fun plan(
                        request: TurnContextRequest,
                        control: RunControlConfig,
                        settings: ProviderContextSettings,
                        force: Boolean,
                        inputScale: Double,
                    ): ContextCompactionPlan? = error("No history read is allowed for a mismatched snapshot")
                }
            val round =
                ContextCompactionRound(
                    source,
                    ContextSummaryFormat("Summarize.", "Facts."),
                    control,
                    settings,
                    false,
                )
            val prepared = round.prepare(input)
            assertEquals("PROMPT_SNAPSHOT_MISMATCH", prepared.failure?.errorCode)
            assertNull(prepared.request)
        }
}
