package com.helix.app.application.tools

import com.helix.app.application.tools.DurableToolLoopProgress
import com.helix.app.application.tools.ToolDispatchCall
import com.helix.app.application.tools.ToolLoopResult
import com.helix.core.agent.ToolLoopProgress
import com.helix.core.model.Capability
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.TimeNowTool
import com.helix.tools.framework.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableToolLoopProgressTest {
    private val read = TimeNowTool.descriptor().copy(name = ToolName("read"))

    private fun calls(
        state: String = "COMPLETED",
        name: String = "read",
    ) = List(6) {
        ToolDispatchCall("c$it", "turn", "c$it", name, "1", "{}", "a".repeat(64), state, "intent $it")
    }

    private fun result(
        id: String,
        status: String = "SUCCEEDED",
        verified: Boolean = true,
    ) = ToolLoopResult("r$id", id, status, "same result", null, verified)

    private fun decision(
        calls: List<ToolDispatchCall> = calls(),
        descriptor: ToolDescriptor? = read,
        resultFor: (String) -> ToolLoopResult? = { result(it) },
    ) = DurableToolLoopProgress.fromHistory(calls, resultFor) { _, _ -> descriptor }

    @Test fun restoredRowsDetectTheSameLoopDespiteDifferentCallIdsAndIntents() {
        val saved = calls()
        assertEquals(ToolLoopProgress.Decision.STOP, decision(saved))
        assertEquals(ToolLoopProgress.Decision.STOP, decision(saved.map { it.copy() }))
    }

    @Test fun changedFullContentBreaksEqualSummaryLoop() {
        assertEquals(
            ToolLoopProgress.Decision.CONTINUE,
            decision {
                result(it).copy(contentRef = "content-hash-$it")
            },
        )
    }

    @Test fun onlySettledSafeOutcomesContribute() {
        assertEquals(ToolLoopProgress.Decision.STOP, decision(calls("DENIED")) { result(it, "DENIED", false) })
        assertEquals(ToolLoopProgress.Decision.STOP, decision(calls("FAILED")) { result(it, "FAILED", false) })
        for (state in listOf("NEEDS_REVIEW", "RUNNING", "AWAITING_APPROVAL", "INTERRUPTED")) {
            assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(calls(state)))
        }
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision { null })
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision { result(it, verified = false) })
    }

    @Test fun mutationsAndLiveObservationsDoNotTripReadDeduplication() {
        for (operation in listOf(ToolOperationClass.LOCAL_MUTATION, ToolOperationClass.CODE_EXECUTION)) {
            assertEquals(
                ToolLoopProgress.Decision.CONTINUE,
                decision(descriptor = read.copy(operationClass = operation)),
            )
        }
        // A remote or renamed ordinary tool cannot acquire trusted semantics from its name.
        assertEquals(ToolLoopProgress.Decision.STOP, decision(calls(name = "goal.get")))
        for (capability in listOf(Capability.ACCESSIBILITY_AUTOMATION, Capability.MOBILE_USE)) {
            assertEquals(
                if (capability ==
                    Capability.MOBILE_USE
                ) {
                    ToolLoopProgress.Decision.REASSESS
                } else {
                    ToolLoopProgress.Decision.CONTINUE
                },
                decision(
                    descriptor =
                        read.copy(
                            requiredCapabilities = setOf(capability),
                        ),
                ),
            )
        }
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(descriptor = null))
    }

    @Test fun successfulPhoneCyclesWithFreshTokensAndContentPromptReviewWithoutStopping() {
        val names = listOf("ui.device", "ui.find", "ui.click", "ui.screenshot")
        val saved =
            List(24) { index ->
                calls()[0].copy(
                    id = "call-$index",
                    callId = "call-$index",
                    name = names[index % 4],
                    argsHash = "fresh-token-$index",
                )
            }
        val decision =
            DurableToolLoopProgress.fromHistory(saved, { result(it).copy(contentRef = "new-image-$it") }) { name, _ ->
                read.copy(
                    requiredCapabilities = setOf(Capability.MOBILE_USE),
                    operationClass =
                        if (name ==
                            "ui.click"
                        ) {
                            ToolOperationClass.EXTERNAL_ACTION
                        } else if (name ==
                            "ui.screenshot"
                        ) {
                            ToolOperationClass.LOCAL_MUTATION
                        } else {
                            ToolOperationClass.READ_ONLY
                        },
                )
            }
        assertEquals(ToolLoopProgress.Decision.REASSESS, decision)
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(saved.map { it.copy(state = "NEEDS_REVIEW") }))
    }

    @Test fun changedArgumentsAndWriteThenReadPermitFreshVerification() {
        val changed = calls().toMutableList()
        changed[5] = changed[5].copy(argsHash = "b".repeat(64))
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(changed))
        changed[5] = changed[5].copy(state = "RUNNING")
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(changed + calls().first()))
    }

    @Test fun longHistoryLoadsEachBoundedFactOnlyOnce() {
        val saved = List(1000) { index -> calls().first().copy(id = "c$index", callId = "c$index") }
        val loadedResults = mutableListOf<String>()
        var descriptorLoads = 0
        var observationLoads = 0
        val actual =
            DurableToolLoopProgress.fromHistory(
                saved,
                {
                    loadedResults += it
                    result(it).copy(contentRef = "fresh-$it")
                },
                {
                    observationLoads++
                    null
                },
            ) { _, _ ->
                descriptorLoads++
                read.copy(requiredCapabilities = setOf(Capability.MOBILE_USE))
            }
        assertEquals(ToolLoopProgress.Decision.REASSESS, actual)
        assertEquals(saved.takeLast(ToolLoopProgress.WINDOW).map { it.id }, loadedResults)
        assertEquals(ToolLoopProgress.WINDOW, descriptorLoads)
        assertEquals(ToolLoopProgress.WINDOW, observationLoads)
    }

    @Test fun unsettledOrUnverifiedResultsDoNotLoadTrustedObservationAudit() {
        for (state in listOf("RUNNING", "NEEDS_REVIEW", "DENIED", "FAILED", "COMPLETED")) {
            var observationLoads = 0
            DurableToolLoopProgress.fromHistory(
                calls(state),
                { result(it, verified = false) },
                {
                    observationLoads++
                    null
                },
            ) { _, _ -> read }
            assertEquals("state=$state", 0, observationLoads)
        }
    }

    @Test fun workflowUsesTheSameResultSnapshotAsExactClassifier() {
        val loads = mutableMapOf<String, Int>()
        val actual =
            DurableToolLoopProgress.fromHistory(calls(), { id ->
                val count = (loads[id] ?: 0) + 1
                loads[id] = count
                result(id, verified = count == 1).copy(contentRef = "fresh-$id")
            }) { _, _ -> read.copy(requiredCapabilities = setOf(Capability.MOBILE_USE)) }
        assertEquals(ToolLoopProgress.Decision.REASSESS, actual)
        assertEquals(setOf(1), loads.values.toSet())
    }
}
