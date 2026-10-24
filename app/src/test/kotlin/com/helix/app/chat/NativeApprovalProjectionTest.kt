package com.helix.app.chat

import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalCardUi
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolOperationClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeApprovalProjectionTest {
    private fun card(id: String) =
        ApprovalCardUi(
            approvalId = id,
            bindingHash = "bound-$id",
            toolName = "ui.click",
            sourceRef = "builtin",
            operationClass = ToolOperationClass.PRIVILEGED,
            state = ApprovalCardState.PENDING,
            sourceRes = 1,
            targetRes = 2,
            scope = "scope-a",
            arguments = "{token:original}",
            operationRes = 3,
            profile = SafetyProfile.ADVANCED,
            providerMcpId = null,
            networkOrigin = null,
            residence = null,
            dataCategoryRes = 4,
            boundedRule = null,
            codeOrCommand = null,
            expectedImpact = "click",
            verifierRes = 5,
            confirmationDetail = "confirm",
            terminalDetail = null,
        )

    @Test fun pendingRequestsAreIndependentOfTheOpenConversation() {
        val projection = NativeApprovalProjection()
        val a = card("a")
        projection.register("turn-a", "call-a", a)
        projection.register("turn-b", "call-b", card("b"))
        assertEquals(listOf(a), projection.forTurn("turn-a"))
        projection.remove("a")
        assertTrue(projection.forTurn("turn-a").isEmpty())
        assertEquals(listOf(card("b")), projection.forTurn("turn-b"))
    }

    @Test fun cancellationAndSettlementRemoveOnlyTheirOriginalRequests() {
        val projection = NativeApprovalProjection()
        projection.register("a", "a1", card("a1"))
        projection.register("a", "a2", card("a2"))
        projection.register("b", "b1", card("b1"))
        projection.finishCall("a1")
        assertTrue(projection.forTurn("a").none { it.approvalId == "a1" })
        assertEquals(listOf(card("a2")), projection.forTurn("a"))
        projection.finishTurn("a")
        assertTrue(projection.forTurn("a").isEmpty())
        assertEquals(listOf(card("b1")), projection.forTurn("b"))
        projection.remove("b1")
        assertTrue(projection.forTurn("b").isEmpty())
    }

    @Test fun laterRequestsDoNotReplaceTheVisiblePendingAction() {
        val projection = NativeApprovalProjection()
        projection.register("a", "first", card("z-first"))
        projection.register("a", "second", card("a-later"))
        assertEquals(listOf("z-first", "a-later"), projection.forTurn("a").map { it.approvalId })
        projection.register("a", "first", card("z-first"))
        assertEquals(listOf("z-first", "a-later"), projection.forTurn("a").map { it.approvalId })
        projection.remove("z-first")
        assertEquals(listOf("a-later"), projection.forTurn("a").map { it.approvalId })
    }
}
