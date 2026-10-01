package com.helix.app.chat

import com.helix.core.agent.ToolLoopProgress
import com.helix.core.model.Capability
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
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
        ToolCallEntity("c$it", "turn", "c$it", name, "1", "{}", "a".repeat(64), state, "intent $it")
    }

    private fun result(
        id: String,
        status: String = "SUCCEEDED",
        verified: Boolean = true,
    ) = ToolResultEntity("r$id", id, status, "same result", null, verified)

    private fun decision(
        calls: List<ToolCallEntity> = calls(),
        descriptor: ToolDescriptor? = read,
        resultFor: (String) -> ToolResultEntity? = { result(it) },
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
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(calls(name = "get_goal")))
        assertEquals(
            ToolLoopProgress.Decision.CONTINUE,
            decision(
                descriptor =
                    read.copy(
                        requiredCapabilities = setOf(Capability.ACCESSIBILITY_AUTOMATION),
                    ),
            ),
        )
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(descriptor = null))
    }

    @Test fun changedArgumentsAndWriteThenReadPermitFreshVerification() {
        val changed = calls().toMutableList()
        changed[5] = changed[5].copy(argsHash = "b".repeat(64))
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(changed))
        changed[5] = changed[5].copy(state = "RUNNING")
        assertEquals(ToolLoopProgress.Decision.CONTINUE, decision(changed + calls().first()))
    }
}
