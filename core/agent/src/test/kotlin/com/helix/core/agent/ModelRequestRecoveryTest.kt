package com.helix.core.agent

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestRecoveryTest {
    private fun step(index: Int) =
        listOf(
            ModelEvent.ToolCallStarted(0, ToolCallId("step-$index"), "read"),
            ModelEvent.ToolArgumentsDelta(0, "{\"index\":$index}"),
            ModelEvent.ToolCallFinished(0),
            ModelEvent.Completed("tool_calls"),
        )

    @Test fun retriesOnlyThirteenthRequestAndSixthAttemptCanSucceed() =
        runBlocking {
            val fixture =
                HeadlessLoopFixture { index ->
                    when {
                        index < 12 -> step(index)
                        index < 17 -> listOf(ModelEvent.Error(ModelErrorCode.TRANSPORT, true))
                        else -> listOf(ModelEvent.TextDelta("recovered"), ModelEvent.Completed("stop"))
                    }
                }.also { it.inferenceRecovery = true }
            val result = fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
            assertEquals(TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null)), result)
            assertEquals(18, fixture.requests.size)
            assertEquals(12, fixture.dispatched.sumOf { it.calls.size })
            assertEquals(5, fixture.journal.log.count { it == "diagnostic:model.automatic_retry" })
            assertEquals(
                1,
                fixture.requests
                    .drop(12)
                    .map { it.messages }
                    .distinct()
                    .size,
            )
        }

    @Test fun fiveFailedRetriesEndWithoutRepeatingTwelveEarlierCalls() =
        runBlocking {
            val fixture =
                HeadlessLoopFixture { index ->
                    if (index < 12) step(index) else listOf(ModelEvent.Error(ModelErrorCode.TIMEOUT, true))
                }.also { it.inferenceRecovery = true }
            val result = fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
            assertEquals(TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.FAILED, "TIMEOUT")), result)
            assertEquals(18, fixture.requests.size)
            assertEquals(12, fixture.dispatched.sumOf { it.calls.size })
            assertEquals(5, fixture.journal.log.count { it == "diagnostic:model.automatic_retry" })
        }

    @Test fun authenticationErrorDoesNotRetry() =
        runBlocking {
            val fixture =
                HeadlessLoopFixture { listOf(ModelEvent.Error(ModelErrorCode.AUTH, false)) }
                    .also { it.inferenceRecovery = true }
            fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
            assertEquals(1, fixture.requests.size)
            assertTrue(fixture.dispatched.isEmpty())
        }

    @Test fun compactionDoesNotAddASecondNetworkRetryLayer() =
        runBlocking {
            val fixture =
                HeadlessLoopFixture { listOf(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, true)) }
                    .also { it.inferenceRecovery = true }
            fixture.source =
                object : ContextCompactionSource {
                    override suspend fun inputFloor(model: String) = 0L

                    override suspend fun plan(
                        request: TurnContextRequest,
                        settings: com.helix.provider.api.ProviderContextSettings,
                        force: Boolean,
                        inputScale: Double,
                    ): ContextCompactionPlan? =
                        ContextCompactionPlan(
                            1,
                            SummaryOutputBudget(128, 128).modelRequest(
                                request.model,
                                "Synthetic history",
                                ContextSummaryFormat("Summarize.", "Preserve facts."),
                            ),
                            request,
                            originalInputTokens = 10000,
                        )
                }
            val result = fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
            assertEquals(TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.FAILED, "SERVER_ERROR")), result)
            assertEquals(3, fixture.requests.size)
            assertEquals(0, fixture.journal.log.count { it == "diagnostic:model.automatic_retry" })
            assertTrue(fixture.dispatched.isEmpty())
        }
}
