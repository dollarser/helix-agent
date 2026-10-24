package com.helix.app.chat

import com.helix.app.R
import com.helix.app.application.tools.ToolCallFact
import com.helix.app.application.tools.ToolPresentationFact
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalCardUi
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolOperationClass
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolPresentationTest {
    private val call = ToolCallFact("session", "turn", "call", "read", "{}", "Inspect synthetic fixture")

    private fun screen() =
        MutableStateFlow(
            ChatScreenState(
                emptyList(),
                "session",
                null,
                emptyList(),
                emptyList(),
                null,
                null,
                null,
                null,
            ),
        )

    private fun bridge(screen: MutableStateFlow<ChatScreenState>) =
        ChatToolPresentation(screen, { id, _ -> "label-$id" }, {}, { _, _ -> ToolTimelinePosition(4, 2) })

    @Test fun preparationAndSettlementPreserveOriginalPositionIntentAndDuration() {
        val screen = screen()
        val bridge = bridge(screen)
        bridge.publish(ToolPresentationFact.Prepared(call))
        bridge.publish(ToolPresentationFact.Running(call))
        bridge.publish(ToolPresentationFact.Settled(call, ToolCallState.COMPLETED, "synthetic result", 42))
        val row = screen.value.toolTimeline.single()
        assertEquals("label-${R.string.tool_state_completed}", row.stateLabel)
        assertEquals("synthetic result", row.resultSummary)
        assertEquals(ToolTimelinePosition(4, 2), row.position)
        assertEquals(call.modelIntent, row.modelIntent)
        assertEquals(42L, row.durationMs)
    }

    @Test fun unknownOutcomeIsReviewPresentationAndFrameworkRejectionRemainsFailure() {
        val screen = screen()
        val bridge = bridge(screen)
        bridge.publish(ToolPresentationFact.Settled(call, ToolCallState.NEEDS_REVIEW, "raw diagnostics", 7))
        assertEquals(
            "label-${R.string.tool_state_side_effect_pending}",
            screen.value.toolTimeline
                .single()
                .stateLabel,
        )
        bridge.publish(
            ToolPresentationFact.Settled(
                call,
                ToolCallState.FAILED,
                "bad arguments",
                null,
                denialCode = DispatchOutcomeCode.INVALID_ARGUMENTS,
                preDispatch = true,
            ),
        )
        assertEquals(
            "label-${R.string.tool_state_failed}",
            screen.value.toolTimeline
                .single()
                .stateLabel,
        )
        bridge.publish(
            ToolPresentationFact.Settled(
                call,
                ToolCallState.DENIED,
                "Review required",
                null,
                denialCode = DispatchOutcomeCode.POLICY_DENIED,
                preDispatch = true,
            ),
        )
        assertEquals(
            "label-${R.string.tool_state_denied}",
            screen.value.toolTimeline
                .single()
                .stateLabel,
        )
    }

    @Test fun factsForAnotherSessionCannotRewriteTheOpenConversation() {
        val screen = screen()
        val bridge = bridge(screen)
        val other = call.copy(sessionId = "another-session")
        bridge.publish(ToolPresentationFact.Prepared(other))
        bridge.publish(ToolPresentationFact.Running(other))
        bridge.publish(ToolPresentationFact.Settled(other, ToolCallState.CANCELLED, "cancelled", null))
        assertTrue(screen.value.toolTimeline.isEmpty())
    }

    @Test fun cancellationUsesUiLocaleRatherThanPersistedBusinessDescription() {
        val screen = screen()
        bridge(
            screen,
        ).publish(ToolPresentationFact.Settled(call, ToolCallState.CANCELLED, "stable business reason", null))
        assertEquals(
            "label-${R.string.tool_summary_cancelled_before_start}",
            screen.value.toolTimeline
                .single()
                .resultSummary,
        )
    }

    @Test fun lateDecisionNotificationCannotDowngradeCommittedSuccess() {
        val screen = screen()
        screen.value =
            screen.value.copy(
                toolTimeline =
                    listOf(
                        ToolTimelineRow("turn", "call", "read", "{}", "running", null, card()),
                    ),
            )
        val bridge = bridge(screen)
        bridge.publish(ToolPresentationFact.Settled(call, ToolCallState.COMPLETED, "saved", null))
        bridge.publish(ToolPresentationFact.ApprovalDecided("approval", ApprovalDecision.APPROVED))
        bridge.publish(ToolPresentationFact.ApprovalDecisionFailed("approval"))
        assertEquals(
            ApprovalCardState.SUCCEEDED,
            screen.value.toolTimeline
                .single()
                .card
                ?.state,
        )
    }

    @Test fun lateUserDenialKeepsTheActualUserDecisionVisible() {
        val screen = screen()
        screen.value =
            screen.value.copy(
                toolTimeline =
                    listOf(
                        ToolTimelineRow("turn", "call", "read", "{}", "pending", null, card()),
                    ),
            )
        val bridge = bridge(screen)
        bridge.publish(
            ToolPresentationFact.Settled(
                call,
                ToolCallState.DENIED,
                "Denied",
                null,
                denialCode = DispatchOutcomeCode.APPROVAL_DENIED,
            ),
        )
        bridge.publish(ToolPresentationFact.ApprovalDecided("approval", ApprovalDecision.DENIED))
        assertEquals(
            ApprovalCardState.DENIED,
            screen.value.toolTimeline
                .single()
                .card
                ?.state,
        )
    }

    private fun card() =
        ApprovalCardUi(
            approvalId = "approval",
            bindingHash = "bound",
            toolName = "read",
            sourceRef = "builtin",
            operationClass = ToolOperationClass.READ_ONLY,
            state = ApprovalCardState.PENDING,
            sourceRes = 1,
            targetRes = 2,
            scope = "synthetic",
            arguments = "{}",
            operationRes = 3,
            profile = SafetyProfile.STANDARD,
            providerMcpId = null,
            networkOrigin = null,
            residence = null,
            dataCategoryRes = 4,
            boundedRule = null,
            codeOrCommand = null,
            expectedImpact = "Read fixture",
            verifierRes = 5,
            confirmationDetail = "Synthetic confirmation",
            terminalDetail = null,
        )
}
