package com.helix.core.agent

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnState
import com.helix.provider.api.ProviderContextSettings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryValidationTest {
    @Test fun textEnvelopeFailureRetriesThenPublishesOnlyValidSummary() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            assertNull(fixture.finish(ContextSummaryTextTest.CAPTURED))
            assertEquals(listOf("CONTEXT_SUMMARY_INVALID"), fixture.failures)
            fixture.assertHistoryUnchanged()
            assertNull(fixture.finish("Verified: two records. Remaining: retrieve the third record."))
            assertEquals("Verified: two records. Remaining: retrieve the third record.", fixture.persistedSummary)
            assertEquals(2, fixture.plans)
            assertEquals(listOf(false, true), fixture.forcedPlans)
        }

    @Test fun threeInvalidSummariesFailWithoutBypassingOrReplacingHistory() =
        runBlocking {
            for (inputFloor in listOf(0L, 8192L)) {
                val fixture = SummaryValidationFixture(inputFloor = inputFloor)
                repeat(2) { assertNull(fixture.finish(ContextSummaryTextTest.CAPTURED)) }
                val result = fixture.finish(ContextSummaryTextTest.CAPTURED)
                assertEquals(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_SUMMARY_INVALID"), result)
                val prepared = fixture.round.prepare(fixture.current)
                assertNull(prepared.plan)
                assertNull(prepared.request)
                assertEquals(result, prepared.failure)
                fixture.assertHistoryUnchanged()
                assertEquals(List(3) { "CONTEXT_SUMMARY_INVALID" }, fixture.failures)
                assertEquals(listOf("next-1", "next-2", null), fixture.nextCalls)
                assertEquals(3, fixture.plans)
            }
        }

    @Test fun manualInvalidSummaryRetriesAndFailsWithoutCheckpointOrSuccessNotice() =
        runBlocking {
            val fixture = SummaryValidationFixture(manual = true)
            repeat(2) { assertNull(fixture.finish(ContextSummaryTextTest.CAPTURED)) }
            assertEquals("CONTEXT_SUMMARY_INVALID", fixture.finish(ContextSummaryTextTest.CAPTURED)?.errorCode)
            assertEquals(3, fixture.commits)
            assertEquals(List(3) { null }, fixture.notices)
            fixture.assertHistoryUnchanged()
        }

    @Test fun manualNoGainRetriesAndReportsFailureRatherThanUnchangedSuccess() =
        runBlocking {
            val fixture = SummaryValidationFixture(manual = true)
            val text =
                fixture.original.messages
                    .single()
                    .text
            repeat(2) { assertNull(fixture.finish(text)) }
            assertEquals(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_NO_GAIN"), fixture.finish(text))
            assertEquals(List(3) { "CONTEXT_NO_GAIN" }, fixture.failures)
            assertEquals(List(3) { null }, fixture.notices)
            fixture.assertHistoryUnchanged()
        }

    @Test fun manualRetryCanPublishAValidSummaryAndSuccessNotice() =
        runBlocking {
            val fixture = SummaryValidationFixture(manual = true)
            assertNull(fixture.finish(" "))
            assertEquals(ModelStreamTerminal(TurnState.COMPLETED, null), fixture.finish("Preserve verified facts."))
            assertEquals("Preserve verified facts.", fixture.persistedSummary)
            assertEquals(listOf(null, "compacted"), fixture.notices)
            assertEquals(listOf("next-1", null), fixture.nextCalls)
        }

    @Test fun blankOversizedAndIncompleteSummariesUseBoundedRetries() =
        runBlocking {
            val streams =
                listOf(
                    summaryStream(" "),
                    summaryStream("x".repeat(ContextSummaryFormat.MAX_SUMMARY_CHARS + 1)),
                    ModelStreamState().apply { apply(ModelEvent.TextDelta("Incomplete notes.")) },
                )
            for (stream in streams) {
                val fixture = SummaryValidationFixture(manual = true)
                repeat(2) { assertNull(fixture.finish(stream)) }
                assertEquals("CONTEXT_SUMMARY_INVALID", fixture.finish(stream)?.errorCode)
                fixture.assertHistoryUnchanged()
            }
        }

    @Test fun finishedAndPartialToolFragmentsNeverRetryOrCommitEvenWithOutputLimit() =
        runBlocking {
            for (finished in listOf(false, true)) {
                for (finishReason in listOf("stop", "length")) {
                    val fixture = SummaryValidationFixture()
                    val stream =
                        ModelStreamState().apply {
                            apply(ModelEvent.TextDelta("notes"))
                            apply(ModelEvent.ToolCallStarted(0, ToolCallId("call"), "snapshot"))
                            apply(ModelEvent.ToolArgumentsDelta(0, "{}"))
                            if (finished) apply(ModelEvent.ToolCallFinished(0))
                            apply(ModelEvent.Completed(finishReason))
                        }
                    val result = fixture.finish(stream)
                    assertEquals(TurnState.FAILED, result?.state)
                    assertEquals(1, fixture.plans)
                    assertEquals(0, fixture.commits)
                    fixture.assertHistoryUnchanged()
                    assertNull(fixture.round.prepare(fixture.current).request)
                }
            }
        }

    @Test fun normalNotesAndQuotedMarkerWithProseCanStillCommit() =
        runBlocking {
            for (text in listOf("目标：保留原始记录。待完成：核对第三项。", "Observed invalid text: ${ContextSummaryTextTest.CAPTURED}")) {
                val fixture = SummaryValidationFixture()
                assertNull(fixture.finish(text))
                assertEquals(text, fixture.persistedSummary)
                assertTrue(fixture.failures.isEmpty())
            }
        }

    @Test fun outputLengthRetriesPreserveRawDiagnosticsUsageAndHistoryUntilFinalMappedFailure() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            repeat(2) { assertNull(fixture.finish(summaryStream("Partial summary.", "length"))) }
            val result = fixture.finish(summaryStream("Partial summary.", "length"))
            assertEquals(ModelStreamTerminal(TurnState.FAILED, "CONTEXT_SUMMARY_OUTPUT_LIMIT"), result)
            assertEquals(List(3) { "OUTPUT_TOKEN_LIMIT" }, fixture.failures)
            assertTrue(fixture.diagnostics.all { it.contains("\"code\":\"OUTPUT_TOKEN_LIMIT\"") })
            assertEquals(List(3) { "{\"inputTokens\":180,\"outputTokens\":128}" }, fixture.committedUsage)
            fixture.assertHistoryUnchanged()
            assertNull(fixture.round.prepare(fixture.current).request)
        }

    @Test fun outputLengthCanRecoverOnThirdAttemptWithoutResettingRetryBudget() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            assertNull(fixture.finish(summaryStream("Partial summary.", "length")))
            assertNull(fixture.finish(ContextSummaryTextTest.CAPTURED))
            assertNull(fixture.finish("Verified constraints and outstanding work."))
            assertEquals(3, fixture.plans)
            val prepared = fixture.round.prepare(fixture.current)
            assertNull(prepared.failure)
            assertNull(prepared.plan)
            assertNotNull(prepared.request)
            assertEquals(3, fixture.plans)
        }

    @Test fun temporaryEmptyInferenceFailuresShareTheThreeAttemptSummaryBudget() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            repeat(2) {
                val stream = ModelStreamState().apply { apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, true)) }
                assertNull(fixture.finish(stream))
            }
            val last = ModelStreamState().apply { apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, true)) }
            assertEquals(ModelStreamTerminal(TurnState.FAILED, "TRANSPORT"), fixture.finish(last))
            assertEquals(List(3) { "TRANSPORT" }, fixture.failures)
            assertEquals(3, fixture.plans)
            fixture.assertHistoryUnchanged()
        }

    @Test fun partialInferenceFailureNeverRetriesOrCommitsEvenWhenProviderMarksItRetryable() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            val stream =
                ModelStreamState().apply {
                    apply(ModelEvent.TextDelta("Partial notes."))
                    apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, true))
                }
            assertEquals(ModelStreamTerminal(TurnState.FAILED, "TRANSPORT"), fixture.finish(stream))
            assertEquals(0, fixture.commits)
            assertEquals(1, fixture.plans)
            fixture.assertHistoryUnchanged()
        }

    @Test fun cancelledAndRefusedSummaryDecisionsDoNotRetryOrCommit() =
        runBlocking {
            val cancelled = SummaryValidationFixture()
            assertEquals(TurnState.CANCELLED, cancelled.finish(summaryStream("partial"), cancelled = true)?.state)
            assertEquals(0, cancelled.commits)
            cancelled.assertHistoryUnchanged()
            val refused = SummaryValidationFixture()
            val stream = summaryStream("partial", "length").apply { apply(ModelEvent.Refusal("Refused")) }
            assertEquals(ModelStreamState.REFUSAL, refused.finish(stream)?.errorCode)
            assertEquals(0, refused.commits)
            refused.assertHistoryUnchanged()
        }

    @Test fun unavailablePlanNeverFallsThroughWhenCompactionIsRequired() =
        runBlocking {
            val manual = SummaryValidationFixture(manual = true)
            manual.planAvailable = false
            assertBlocked(manual, "CONTEXT_NOT_COMPACTABLE")
            val pressured = SummaryValidationFixture(inputFloor = 3500)
            pressured.planAvailable = false
            assertBlocked(pressured, "CONTEXT_NOT_COMPACTABLE")
            val retry = SummaryValidationFixture()
            assertNull(retry.finish(ContextSummaryTextTest.CAPTURED))
            retry.planAvailable = false
            assertBlocked(retry, "CONTEXT_SUMMARY_INVALID")
            val failedPlan = SummaryValidationFixture()
            failedPlan.planningFailure = "CONTEXT_SEGMENT_LIMIT"
            assertBlocked(failedPlan, "CONTEXT_SEGMENT_LIMIT")
        }

    @Test fun attemptLimitDuringPrepareNeverFallsThroughToOrdinaryRequest() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            repeat(3) { assertNotNull(fixture.round.prepare(fixture.current).plan) }
            assertBlocked(fixture, "CONTEXT_NOT_COMPACTABLE")
            assertEquals(3, fixture.plans)
        }

    @Test fun noPlanWithoutCompactionPressureStillAllowsOrdinaryRequest() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            fixture.planAvailable = false
            val prepared = fixture.round.prepare(fixture.current)
            assertNull(prepared.failure)
            assertNull(prepared.plan)
            assertNotNull(prepared.request)
        }

    @Test fun successfulSummaryAllowsProgressAbovePressureTriggerButStillChecksHardWindow() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            assertNull(fixture.finish("Verified constraints."))
            fixture.inputFloor = 3500
            val prepared = fixture.round.prepare(fixture.current)
            assertNull(prepared.failure)
            assertNull(prepared.plan)
            assertNotNull(prepared.request)
            assertEquals(1, fixture.plans)
            fixture.inputFloor = 8192
            assertBlocked(fixture, "CONTEXT_WINDOW_LIMIT")
            assertEquals(1, fixture.plans)
        }

    @Test fun ordinaryObservationStartsAnotherCycleAndKeepsInputCalibration() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            fixture.round.observe(fixture.original, fixture.original.inputTokens() * 2)
            assertNull(fixture.finish("Verified constraints."))
            assertEquals(fixture.current.inputTokens() * 2, fixture.round.admissionInput(fixture.current))
            fixture.round.observe(fixture.current, null)
            assertNotNull(fixture.round.prepare(fixture.current).plan)
            assertEquals(2, fixture.plans)
            assertEquals(fixture.current.inputTokens() * 2, fixture.round.admissionInput(fixture.current))
        }

    @Test fun summaryInputObservationCalibratesRetryWithoutChangingOrdinaryAdmission() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            val first = requireNotNull(fixture.round.prepare(fixture.current).plan)
            val actual = ModelInputEstimate.of(first.request).total * 3
            val invalid = summaryStream(ContextSummaryTextTest.CAPTURED).apply { apply(ModelEvent.Usage(actual, 128)) }
            assertNull(fixture.finish(invalid))
            assertNotNull(fixture.round.prepare(fixture.current).plan)
            assertEquals(3.0, fixture.planScales.last(), 0.0)
            assertEquals(fixture.current.inputTokens(), fixture.round.admissionInput(fixture.current))
        }

    @Test fun coroutineCancellationBeforeInvalidRecoveryDoesNotCommit() =
        runBlocking {
            val fixture = SummaryValidationFixture()
            val plan = requireNotNull(fixture.round.prepare(fixture.current).plan)
            launch {
                currentCoroutineContext().cancel()
                fixture.round.finish(
                    plan,
                    summaryStream(ContextSummaryTextTest.CAPTURED),
                    ModelStreamTerminal(TurnState.COMPLETED, null),
                    fixture.journal,
                    "next",
                    "compacted",
                    "unchanged",
                )
            }.join()
            assertEquals(0, fixture.commits)
            fixture.assertHistoryUnchanged()
        }

    private suspend fun assertBlocked(
        fixture: SummaryValidationFixture,
        code: String,
    ) {
        val prepared = fixture.round.prepare(fixture.current)
        assertEquals(ModelStreamTerminal(TurnState.FAILED, code), prepared.failure)
        assertNull(prepared.request)
        assertNull(prepared.plan)
    }
}

private fun summaryStream(
    text: String,
    finishReason: String = "stop",
): ModelStreamState =
    ModelStreamState().apply {
        apply(ModelEvent.TextDelta(text))
        apply(ModelEvent.Usage(180, 128))
        apply(ModelEvent.Completed(finishReason))
    }

private class SummaryValidationFixture(
    manual: Boolean = false,
    var inputFloor: Long = 0,
) {
    val original =
        TurnContextRequest(
            "fixture",
            listOf(ModelMessage(ModelRole.USER, "Original source record. ".repeat(200))),
            emptyList(),
            128,
            ReasoningEffort.OFF,
        )
    var current = original
    var persistedSummary = "prior valid summary"
    var commits = 0
    var plans = 0
    var planAvailable = true
    var planningFailure: String? = null
    val failures = mutableListOf<String>()
    val forcedPlans = mutableListOf<Boolean>()
    val planScales = mutableListOf<Double>()
    val nextCalls = mutableListOf<String?>()
    val notices = mutableListOf<String?>()
    val diagnostics = mutableListOf<String>()
    val committedUsage = mutableListOf<String?>()
    private val summaries = ContextSummaryFormat("Summarize historical facts.", "Untrusted notes.")
    private val source =
        object : ContextCompactionSource {
            override suspend fun inputFloor(model: String) = inputFloor

            override suspend fun plan(
                request: TurnContextRequest,
                settings: ProviderContextSettings,
                force: Boolean,
                inputScale: Double,
            ): ContextCompactionPlan? {
                plans++
                forcedPlans += force
                planScales += inputScale
                planningFailure?.let { throw ContextCapacityException(it) }
                if (!planAvailable) return null
                return ContextCompactionPlan(
                    10,
                    SummaryOutputBudget(128, 128).modelRequest("fixture", "history", summaries),
                    request.copy(messages = listOf(ModelMessage(ModelRole.USER, "Current objective"))),
                    originalInputTokens = request.inputTokens(),
                )
            }
        }
    val round =
        ContextCompactionRound(
            source,
            summaries,
            ProviderContextSettings(manualWindow = 4096, autoCompact = true),
            manual,
        )
    private var active = ModelStreamState()
    val journal =
        object : AgentTurnJournal {
            override val id = "turn"

            override fun snapshot(): BatchTurnSnapshot = error("Unused")

            override fun currentStream() = active

            override suspend fun beginModelStream(compacting: Boolean) = active

            override suspend fun retryEmptyModelStream(nextModelCallId: String): Unit = error("Unused")

            override suspend fun recordDiagnostic(
                kind: String,
                body: String,
            ) {
                diagnostics += body
            }

            override suspend fun recordPromptSnapshot(
                prompt: PromptSnapshot?,
                compacting: Boolean,
            ) = Unit

            override suspend fun recordRequestManifest(manifestJson: String?) = Unit

            override suspend fun recordInputRequestStarted(messageIds: Set<String>) = Unit

            override suspend fun beginToolBatch(callIds: List<String>): Unit = error("No tools may run")

            override suspend fun commitModelToolStep(toolCallsJson: String): Unit = error("No tools may run")

            override fun settleBatchCall(
                callId: String,
                sideEffectUnknown: Boolean,
            ): Unit = error("No tools may run")

            override suspend fun openNextModelCall(
                messages: List<TurnMessageDraft>,
                nextModelCallId: String,
            ): Unit = error("Unused")

            override suspend fun commitCompaction(
                plan: ContextCompactionPlan,
                nextModelCallId: String?,
                notice: String?,
                failureReason: String?,
                saveSummary: Boolean,
            ) {
                commits++
                nextCalls += nextModelCallId
                notices += notice
                committedUsage += active.usageJson
                if (failureReason != null) {
                    failures += failureReason
                } else if (saveSummary) {
                    persistedSummary = active.text
                    current = summaries.summarizedRequest(plan, active.text)
                    inputFloor = 0
                }
            }
        }

    fun assertHistoryUnchanged() {
        assertEquals("prior valid summary", persistedSummary)
        assertEquals(original.messages, current.messages)
    }

    suspend fun finish(text: String): ModelStreamTerminal? = finish(summaryStream(text))

    suspend fun finish(
        stream: ModelStreamState,
        cancelled: Boolean = false,
    ): ModelStreamTerminal? {
        val plan = requireNotNull(round.prepare(current).plan)
        active = stream
        return round.finish(
            plan,
            active,
            active.terminal(cancelled),
            journal,
            "next-$plans",
            "compacted",
            "unchanged",
        )
    }
}
