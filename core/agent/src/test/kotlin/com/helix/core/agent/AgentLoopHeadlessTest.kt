package com.helix.core.agent

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopHeadlessTest {
    @Test fun oneProductionLoopCompletesWithWholeAndFragmentedProviderStreams() =
        runBlocking {
            listOf(false, true).forEach { fragmented ->
                val fixture =
                    HeadlessLoopFixture { index ->
                        if (index ==
                            0
                        ) {
                            calls(fragmented)
                        } else {
                            listOf(ModelEvent.TextDelta("14"), ModelEvent.Usage(30, 2), ModelEvent.Completed("stop"))
                        }
                    }
                val result = fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
                assertEquals(TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.COMPLETED, null)), result)
                assertEquals(2, fixture.requests.size)
                assertEquals(2, fixture.modelCallCount)
                assertEquals(54L, fixture.tokenCount)
                assertEquals(
                    listOf("wire-a", "wire-b"),
                    fixture.requests
                        .last()
                        .messages
                        .filter {
                            it.role == ModelRole.TOOL
                        }.map { it.toolCallId?.value },
                )
                assertTrue(
                    fixture.dispatched
                        .single()
                        .calls
                        .all { it.callId.startsWith("local-") },
                )
                assertEquals(AgentLoopEvent.TextChanged("turn", "14"), fixture.events.last())
                assertTrue(fixture.journal.log.indexOf("results-committed") < fixture.journal.log.indexOf("wire:1"))
            }
        }

    @Test fun stopBeforeFirstRequestDoesNotSpendOrExecute() =
        runBlocking {
            val fixture = HeadlessLoopFixture { error("must not call model") }.also { it.cancelled = true }
            assertEquals(
                TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.CANCELLED, null)),
                fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control),
            )
            assertTrue(fixture.requests.isEmpty())
            assertTrue(fixture.dispatched.isEmpty())
            assertEquals(0, fixture.modelCallCount)
        }

    @Test fun stopDuringModelStreamNeverExecutesAnAlreadyDecodedToolCall() =
        runBlocking {
            val fixture = HeadlessLoopFixture { calls(false) }.also { it.cancelAfterEvent = 0 }
            assertEquals(
                TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.CANCELLED, null)),
                fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control),
            )
            assertEquals(1, fixture.requests.size)
            assertTrue(fixture.dispatched.isEmpty())
        }

    @Test fun unknownOutcomeParksOriginalIdentityWithoutAnotherModelCall() =
        runBlocking {
            val fixture = HeadlessLoopFixture { calls(false) }.also { it.unknown = true }
            val result = fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control)
            assertEquals(
                TurnLoopResult.ParkedForReview(
                    fixture.dispatched
                        .single()
                        .calls
                        .map { it.callId },
                ),
                result,
            )
            assertEquals(1, fixture.requests.size)
            assertFalse("results-committed" in fixture.journal.log)
        }

    @Test fun reversedToolReceiptsCannotBeBackfilledAsCorrectHistory() {
        val fixture = HeadlessLoopFixture { calls(false) }.also { it.reverseResults = true }
        val failure =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control) }
            }
        assertEquals("TOOL_BATCH_RESULT_IDENTITY_MISMATCH", failure.message)
        assertEquals(1, fixture.requests.size)
        assertFalse("results-committed" in fixture.journal.log)
    }

    @Test fun failingManifestStorageNeverStartsTheWireRequest() {
        val fixture = HeadlessLoopFixture { error("must not send") }.also { it.journal.failManifest = true }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control) }
        }
        assertTrue(fixture.requests.isEmpty())
        assertTrue(fixture.dispatched.isEmpty())
    }

    @Test fun noProgressFinalResponseCannotStartMoreTools() =
        runBlocking {
            val fixture = HeadlessLoopFixture { calls(false) }.also { it.stopProgress = true }
            assertEquals(
                TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.FAILED, "TOOL_LOOP_NO_PROGRESS")),
                fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control),
            )
            assertEquals(2, fixture.requests.size)
            assertTrue(
                fixture.requests
                    .last()
                    .tools
                    .isEmpty(),
            )
            assertEquals(1, fixture.dispatched.size)
        }

    @Test fun resumedLedgerDoesNotResetModelCallBudget() =
        runBlocking {
            val fixture = HeadlessLoopFixture { error("budget must block") }
            fixture.restored = LoopUsageSnapshot("fixture", fixture.control.budgets.maxModelCalls, 42, 0)
            assertEquals(
                TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.FAILED, "MODEL_CALL_LIMIT")),
                fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control),
            )
            assertTrue(fixture.requests.isEmpty())
        }

    @Test fun truncatedArgumentsOrLengthStopNeverReachDispatcher() =
        runBlocking {
            val fixture = HeadlessLoopFixture { calls(false).dropLast(1) + ModelEvent.Completed("length") }
            assertEquals(
                TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.FAILED, "OUTPUT_TOKEN_LIMIT")),
                fixture.loop().runToolLoop("s", fixture.journal, "p", null, fixture.control),
            )
            assertTrue(fixture.dispatched.isEmpty())
        }

    private fun calls(fragmented: Boolean): List<ModelEvent> =
        buildList {
            listOf("wire-a", "wire-b").forEachIndexed { index, id ->
                add(ModelEvent.ToolCallStarted(index, ToolCallId(id), "read"))
                if (fragmented) {
                    add(ModelEvent.ToolArgumentsDelta(index, "{"))
                    add(ModelEvent.ToolArgumentsDelta(index, "}"))
                } else {
                    add(ModelEvent.ToolArgumentsDelta(index, "{}"))
                }
                add(ModelEvent.ToolCallFinished(index))
            }
            add(ModelEvent.Usage(20, 2))
            add(ModelEvent.Completed("tool_calls"))
        }
}
