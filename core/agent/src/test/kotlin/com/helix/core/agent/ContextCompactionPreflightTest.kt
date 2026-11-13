package com.helix.core.agent

import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic text with the measured 16K request costs; selection uses the production compiler. */
class ContextCompactionPreflightTest {
    @Test fun schemaDominatedRequestWithoutEligibleHistoryRemainsAnOrdinaryRequest() =
        runBlocking {
            val fixture = PreflightFixture(targetRows(), 10195)
            val original = fixture.request()
            assertEquals(ModelInputEstimate(2789, 943, 10195, 0), ModelInputEstimate.of(original.modelRequest()))
            assertTrue(
                ContextCapacity.shouldCompact(original, fixture.settings, Long.MAX_VALUE, original.inputTokens()),
            )
            repeat(4) { assertOrdinary(fixture.round.prepare(original), original) }
            assertTrue(ContextSegments.candidates(fixture.rows, "current").isEmpty())
        }

    @Test fun impossibleGainIncludesTheFullReplayWrapperAndDoesNotConsumeAttempts() =
        runBlocking {
            val fixture = PreflightFixture(sourceRows(), 8276)
            val original = fixture.request()
            assertEquals(ModelInputEstimate(2789, 1041, 8276, 0), ModelInputEstimate.of(original.modelRequest()))
            val plan = requireNotNull(fixture.plan(original))
            assertEquals(472L, ModelInputEstimate.of(plan.retainedRequest.modelRequest()).history)
            assertEquals(569L, original.inputTokens() - plan.retainedRequest.inputTokens())
            val emptyAfter = fixture.summaries.summarizedRequest(plan, "").inputTokens()
            assertEquals(119L, original.inputTokens() - emptyAfter)
            assertEquals(605L, original.inputTokens() / 20)
            assertFalse(fixture.summaries.hasUsefulGain(plan, ""))
            repeat(4) { assertOrdinary(fixture.round.prepare(original), original) }

            // New complete history makes the same pressure genuinely compactable on the next request.
            fixture.rows =
                fixture.rows.map { row ->
                    if (row.sequence == 4L) {
                        val message = row.messages.single().copy(text = row.messages.single().text + "x".repeat(6000))
                        row.copy(content = message.text, messages = listOf(message))
                    } else {
                        row
                    }
                }
            val grown = fixture.request()
            val prepared = fixture.round.prepare(grown)
            assertNull(prepared.failure)
            assertNotNull(prepared.plan)
            assertTrue(requireNotNull(prepared.request).tools.isEmpty())
            val retained = requireNotNull(prepared.plan).retainedRequest.messages
            assertEquals(listOf("step-7"), retained.flatMap { it.toolCalls }.map { it.id.value })
            assertEquals(listOf("step-7"), retained.mapNotNull { it.toolCallId?.value })
            assertEquals(
                original.messages.filter { it.role == ModelRole.USER },
                retained.filter {
                    it.role ==
                        ModelRole.USER
                },
            )
        }

    @Test fun noCandidateDeferralHonorsTheExactHardWindowIncludingMeasuredInputFloor() =
        runBlocking {
            val fixture = PreflightFixture(targetRows(), 10195)
            val original = fixture.request()
            fixture.inputFloor = 16384 - 1024
            assertOrdinary(fixture.round.prepare(original), original)
            fixture.inputFloor++
            val blocked = fixture.round.prepare(original)
            assertNull(blocked.request)
            assertEquals("CONTEXT_WINDOW_LIMIT", blocked.failure?.errorCode)
            fixture.inputFloor = 0
            assertEquals(blocked.failure, fixture.round.prepare(original).failure)
        }

    @Test fun hardPressureWithAnImpossiblePlanNeverAdmitsTheOriginalRequest() =
        runBlocking {
            val fixture = PreflightFixture(sourceRows(), 8276)
            fixture.inputFloor = 16384
            val prepared = fixture.round.prepare(fixture.request())
            assertNotNull(prepared.plan)
            assertTrue(requireNotNull(prepared.request).tools.isEmpty())
        }

    @Test fun manualCompactionStillRequiresAPlanAndAttemptsSummariesWithoutSoftDeferral() =
        runBlocking {
            val noHistory = PreflightFixture(targetRows(), 10195, manual = true)
            val blocked = noHistory.round.prepare(noHistory.request())
            assertNull(blocked.request)
            assertEquals("CONTEXT_NOT_COMPACTABLE", blocked.failure?.errorCode)
            val noGain = PreflightFixture(sourceRows(), 8276, manual = true)
            assertNotNull(noGain.round.prepare(noGain.request()).plan)
        }

    @Test fun reorderedHistoryRemainsAFailureRatherThanBeingTreatedAsNoCandidate() =
        runBlocking {
            val fixture = PreflightFixture(sourceRows(), 8276)
            val original = fixture.request()
            val messages = original.messages.toMutableList()
            val first = messages[1]
            messages[1] = messages[2]
            messages[2] = first
            val blocked = fixture.round.prepare(original.copy(messages = messages))
            assertNull(blocked.request)
            assertEquals("CONTEXT_NOT_COMPACTABLE", blocked.failure?.errorCode)
            assertEquals(blocked.failure, fixture.round.prepare(original).failure)
        }

    @Test fun messageCountPressureCanDeferButTheHardMessageLimitCannot() =
        runBlocking {
            val rows = (0L..510L).map { textRow(it, ModelRole.USER, 1) }
            val fixture = PreflightFixture(rows, 100, window = 100_000)
            val atLimit = fixture.request()
            assertEquals(512, atLimit.messages.size)
            assertOrdinary(fixture.round.prepare(atLimit), atLimit)
            fixture.rows = rows + textRow(511, ModelRole.USER, 1)
            val blocked = fixture.round.prepare(fixture.request())
            assertNull(blocked.request)
            assertEquals("CONTEXT_MESSAGE_LIMIT", blocked.failure?.errorCode)
        }

    @Test fun noEligibleHistoryStillValidatesTheSourceBeforeOrdinaryAdmission() =
        runBlocking {
            val fixture = PreflightFixture(targetRows(), 10195)
            val original = fixture.request()
            assertTrue(ContextSegments.candidates(fixture.rows, "current").isEmpty())
            val messages = original.messages.toMutableList()
            messages[1] = messages[1].copy(text = "Changed source projection")
            val blocked = fixture.round.prepare(original.copy(messages = messages))
            assertNull(blocked.request)
            assertEquals("CONTEXT_NOT_COMPACTABLE", blocked.failure?.errorCode)
            assertEquals(blocked.failure, fixture.round.prepare(original).failure)
        }

    @Test fun disabledAutomaticCompactionDoesNotTurnPressureIntoAMaintenanceRequest() =
        runBlocking {
            val fixture = PreflightFixture(sourceRows(), 8276, autoCompact = false)
            val original = fixture.request()
            assertOrdinary(fixture.round.prepare(original), original)
            fixture.inputFloor = 16384
            val blocked = fixture.round.prepare(original)
            assertNull(blocked.request)
            assertEquals("CONTEXT_WINDOW_LIMIT", blocked.failure?.errorCode)
        }

    private fun assertOrdinary(
        prepared: PreparedContext,
        original: TurnContextRequest,
    ) {
        assertNull(prepared.failure)
        assertNull(prepared.plan)
        assertEquals(original.modelRequest(), prepared.request)
        assertEquals(original, prepared.context)
    }

    private fun sourceRows() =
        listOf(
            textRow(0, ModelRole.USER, 83),
            textRow(1, ModelRole.ASSISTANT, 12),
            textRow(2, ModelRole.USER, 981),
            callRow(3, 64),
            resultRow(4, 1185),
            callRow(5, 314),
            resultRow(6, 459),
            callRow(7, 54),
            resultRow(8, 435),
        )

    private fun targetRows() =
        listOf(
            textRow(0, ModelRole.USER, 83),
            textRow(1, ModelRole.ASSISTANT, 12),
            textRow(2, ModelRole.USER, 1075),
            callRow(3, 64),
            resultRow(4, 2218),
        )
}

private class PreflightFixture(
    var rows: List<ContextSourceRow>,
    private val toolTokens: Long,
    manual: Boolean = false,
    autoCompact: Boolean = true,
    window: Long = 16384,
) {
    val settings = ProviderContextSettings(manualWindow = window, autoCompact = autoCompact)
    var inputFloor = 0L
    private val emptyFrameBytes =
        TokenEstimator.utf8Bytes(ContextSummaryFormat("Summarize.", "").summaryMessage(ContextCheckpoint(6, "")).text)
    val summaries = ContextSummaryFormat("Summarize.", "c".repeat((1736 - emptyFrameBytes).toInt()))
    private val compiler = ContextCompiler(summaries)
    private val source =
        object : ContextCompactionSource {
            override suspend fun inputFloor(model: String) = inputFloor

            override suspend fun plan(
                request: TurnContextRequest,
                settings: ProviderContextSettings,
                force: Boolean,
                inputScale: Double,
            ) = compiler.plan(snapshot(), request, settings, force, inputScale)
        }
    val round = ContextCompactionRound(source, summaries, settings, manual)

    fun plan(request: TurnContextRequest) = compiler.plan(snapshot(), request, settings, true)

    private fun snapshot() = ContextSourceSnapshot(rows, null, "current", null, inputFloor)

    fun request(): TurnContextRequest {
        val prefix = "{\"type\":\"object\",\"description\":\""
        val suffix = "\"}"
        val body = "x".repeat(((toolTokens - 32) * 4 - prefix.length - suffix.length - 1).toInt())
        val tools = listOf(ModelToolSchema(ToolName("read"), "x", prefix + body + suffix))
        return TurnContextRequest(
            "fixture",
            listOf(ModelMessage(ModelRole.SYSTEM, "s".repeat((2789 - 16) * 4))) + rows.flatMap { it.mappedMessages() },
            tools,
            1024,
            ReasoningEffort.OFF,
        )
    }
}

private fun textRow(
    sequence: Long,
    role: ModelRole,
    bytes: Int,
): ContextSourceRow {
    val message = ModelMessage(role, "x".repeat(bytes))
    return sourceRow(sequence, message, "TEXT")
}

private fun callRow(
    sequence: Long,
    argumentBytes: Int,
): ContextSourceRow {
    val arguments = "{\"v\":\"" + "x".repeat(argumentBytes - 8) + "\"}"
    val call = AssistantToolCall(ToolCallId("step-$sequence"), ToolName("read"), arguments)
    return sourceRow(sequence, ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)), "TOOL_CALLS")
}

private fun resultRow(
    sequence: Long,
    bytes: Int,
): ContextSourceRow =
    sourceRow(
        sequence,
        ModelMessage(
            ModelRole.TOOL,
            "x".repeat(bytes),
            toolCallId = ToolCallId("step-${sequence - 1}"),
            toolName = ToolName("read"),
        ),
        "TOOL_RESULT",
    )

private fun sourceRow(
    sequence: Long,
    message: ModelMessage,
    kind: String,
) = ContextSourceRow(
    "message-$sequence",
    if (sequence < 2) "previous" else "current",
    sequence,
    message.role.name,
    kind,
    message.text + message.toolCalls.joinToString("") { it.argumentsJson },
    0,
    kind == "TOOL_CALLS",
    listOf(message),
)
