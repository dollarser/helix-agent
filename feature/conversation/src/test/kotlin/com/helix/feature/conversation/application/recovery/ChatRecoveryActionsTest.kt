package com.helix.feature.conversation.application.recovery

import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.ProotRecoveredOutput
import com.helix.feature.conversation.presentation.ProotRecoveryReport
import com.helix.feature.conversation.presentation.ProotRecoveryStatus
import com.helix.feature.conversation.presentation.ToolTimelineRow
import com.helix.provider.api.SubscriptionRecoveredOutput
import com.helix.provider.api.SubscriptionRecoveryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRecoveryActionsTest {
    @Test fun hiddenOriginalTurnUsesPersistedIdentitiesAndUnknownJobsOnlyReadLocalOutput() =
        runBlocking {
            val calls = mutableListOf<String>()
            val proot =
                FakeProot(
                    inspect = { turn, call, stop ->
                        calls += "inspect:$turn:$call:$stop"
                        ProotRecoveryReport(
                            0,
                            status =
                                if (call == "success") ProotRecoveryStatus.SUCCEEDED else ProotRecoveryStatus.UNKNOWN,
                        )
                    },
                    collect = { turn, call, local ->
                        calls += "collect:$turn:$call:$local"
                        ProotRecoveredOutput("original output", "", false, emptyList(), acknowledged = false)
                    },
                )
            val actions =
                ChatRecoveryActions(
                    store = facts(listOf("unknown", "success")),
                    proot = proot,
                    workScope = this,
                    screenState = MutableStateFlow(screen()),
                    subscriptionRecovery = { turn, call, stop ->
                        calls += "subscription:$turn:$call:$stop"
                        SubscriptionRecoveryStatus.UNKNOWN
                    },
                    subscriptionResultRecovery = { turn, call, local ->
                        calls += "subscription-output:$turn:$call:$local"
                        SubscriptionRecoveredOutput.fromText("retained evidence")
                    },
                )
            val evidence = actions.inspectOriginal("original")
            assertEquals(
                listOf(
                    "subscription:original:model:false",
                    "subscription-output:original:model:false",
                    "inspect:original:unknown:false",
                    "collect:original:unknown:true",
                    "inspect:original:success:false",
                    "collect:original:success:false",
                ),
                calls,
            )
            assertTrue(evidence.contains("\"status\":\"UNKNOWN\""))
            assertTrue(evidence.contains("\"originalReceiptAcknowledged\":false"))
            assertTrue(evidence.contains("retained evidence"))
        }

    @Test fun originalInspectionFailureStaysUnknownAndDoesNotSuppressAnotherOriginalJob() =
        runBlocking {
            var prootQueries = 0
            val actions =
                ChatRecoveryActions(
                    facts(listOf("unknown")),
                    FakeProot(inspect = { _, _, _ ->
                        prootQueries++
                        error("Runtime unavailable")
                    }),
                    this,
                    MutableStateFlow(screen()),
                    subscriptionRecovery = { _, _, _ -> throw IllegalArgumentException("Lost binding") },
                    subscriptionResultRecovery = { _, _, _ -> error("Failed query cannot reach collection") },
                )
            val evidence = actions.inspectOriginal("original")
            assertEquals(1, prootQueries)
            assertTrue(evidence.contains("UNKNOWN:IllegalArgumentException"))
            assertTrue(evidence.contains("UNKNOWN:IllegalStateException"))
            assertFalse(evidence.contains("SUCCEEDED"))
        }

    @Test fun cancellationPropagatesAndReleasesOriginalSubscriptionLock() =
        runBlocking {
            var attempts = 0
            val actions =
                ChatRecoveryActions(
                    facts(emptyList()),
                    FakeProot(),
                    this,
                    MutableStateFlow(screen()),
                    subscriptionRecovery = { _, _, _ ->
                        if (++attempts == 1) throw CancellationException("Cancelled")
                        SubscriptionRecoveryStatus.ENDED
                    },
                    subscriptionResultRecovery = { _, _, _ -> null },
                )
            try {
                actions.inspectOriginal("original")
                error("Cancellation was swallowed")
            } catch (_: CancellationException) {
                // Cancellation is not converted to UNKNOWN evidence or retried automatically.
            }
            assertTrue(actions.inspectOriginal("original").contains("ENDED"))
            assertEquals(2, attempts)
        }

    @Test fun explicitProotInspectionPreservesTurnIdentityAndCancellationRestoresBusyState() =
        runBlocking {
            val originalReport = ProotRecoveryReport(0, status = ProotRecoveryStatus.RUNNING)
            val first = row("first").copy(prootRecoveryReport = originalReport)
            val second = row("second")
            val state = MutableStateFlow(screen().copy(toolTimeline = listOf(first, second)))
            coroutineScope {
                ChatRecoveryActions(
                    facts(emptyList()),
                    FakeProot(inspect = { turn, call, stop ->
                        assertEquals("first", turn)
                        assertEquals("same-call", call)
                        assertTrue(stop)
                        assertTrue(state.value.toolTimeline[0].prootRecoveryBusy)
                        assertEquals(second, state.value.toolTimeline[1])
                        throw CancellationException("Cancelled while observing")
                    }),
                    this,
                    state,
                    subscriptionRecovery = { _, _, _ -> error("Not a subscription") },
                    subscriptionResultRecovery = { _, _, _ -> error("Not a subscription") },
                ).inspectInterruptedProot("first", "same-call", true)
            }
            assertEquals(listOf(first, second), state.value.toolTimeline)
        }

    private fun facts(prootCallIds: List<String>): RecoveryObservationStore =
        object : RecoveryObservationStore {
            override fun originalTurn(turnId: String): OriginalRecoveryTargets {
                assertEquals("original", turnId)
                return OriginalRecoveryTargets(turnId, "hidden-session", listOf("model"), prootCallIds)
            }

            override fun interruptedSubscriptions(sessionId: String): List<SubscriptionRecoveryFact> =
                error("Original inspection must not read a different visible conversation")
        }

    private fun row(turnId: String) =
        ToolTimelineRow(turnId, "same-call", "bash", "{}", "INTERRUPTED", null, null, prootRecoveryAvailable = true)

    private fun screen() =
        ChatScreenState(
            sessions = emptyList(),
            openSessionId = "unrelated-session",
            selectedModel = null,
            messages = emptyList(),
            toolTimeline = emptyList(),
            activeTurn = null,
            pendingDisclosure = null,
            blockedReason = null,
            retryTargetTurnId = null,
        )

    private class FakeProot(
        private val inspect: (String, String, Boolean) -> ProotRecoveryReport =
            { _, _, _ -> error("Unexpected query") },
        private val collect: (String, String, Boolean) -> ProotRecoveredOutput? =
            { _, _, _ -> error("Unexpected collect") },
    ) : ProotRecoveryPort {
        override val available = true

        override fun inspect(
            turnId: String,
            callId: String,
            stop: Boolean,
        ) = inspect.invoke(turnId, callId, stop)

        override fun collect(
            turnId: String,
            callId: String,
            localOnly: Boolean,
        ) = collect.invoke(turnId, callId, localOnly)
    }
}
