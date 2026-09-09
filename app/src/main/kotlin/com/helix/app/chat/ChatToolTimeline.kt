package com.helix.app.chat

import com.helix.app.R
import com.helix.app.approval.ApprovalCardState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Atomic updates scoped to tool rows; does not own session admission or network jobs. */
@Suppress("LongParameterList")
internal class ChatToolTimeline(
    private val screenState: MutableStateFlow<ChatScreenState>,
    private val strings: (Int, Array<out Any>) -> String,
) {
    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    fun hasCall(callId: String): Boolean = screenState.value.toolTimeline.any { it.callId == callId }

    /**
     * Publishes (or replaces) the timeline row for one call. [card] = null PRESERVES the
     * row's current card: a settle (success / denial / failure) must not wipe the approval
     * card — a user-denied card stays visible in its terminal DENIED state, an approved
     * one in SUCCEEDED (the card is the record of the authorization decision).
     */
    fun publishToolRow(
        turnId: String,
        callId: String,
        toolName: String,
        requestSummary: String,
        stateLabel: String,
        resultSummary: String?,
        card: com.helix.app.approval.ApprovalCardUi?,
    ) {
        // Atomic update: this row mutation races other timeline writers (the card sink
        // runs on a scheduler pool thread; settle/cancel run on the IO scope). A
        // read-modify-write on the whole screen state would let a concurrent write lose
        // this update — and the card is published exactly ONCE, so a lost publish is a
        // turn the user can never approve.
        screenState.update { screen ->
            val preserved =
                card ?: screen.toolTimeline
                    .firstOrNull { it.turnId == turnId && it.callId == callId }
                    ?.card
            screen.copy(
                toolTimeline =
                    screen.toolTimeline
                        .filterNot { it.turnId == turnId && it.callId == callId }
                        .plus(
                            ToolTimelineRow(
                                turnId,
                                callId,
                                toolName,
                                requestSummary,
                                stateLabel,
                                resultSummary,
                                preserved,
                            ),
                        ),
            )
        }
    }

    fun attachCardToRow(
        callId: String,
        card: com.helix.app.approval.ApprovalCardUi,
    ) {
        screenState.update { screen ->
            screen.copy(
                toolTimeline =
                    screen.toolTimeline.map { row ->
                        if (row.callId == callId) {
                            row.copy(card = card, stateLabel = str(R.string.tool_state_awaiting_approval))
                        } else {
                            row
                        }
                    },
            )
        }
    }

    fun updateCard(
        approvalId: String,
        transform: (com.helix.app.approval.ApprovalCardUi) -> com.helix.app.approval.ApprovalCardUi,
    ) {
        screenState.update { screen ->
            screen.copy(
                toolTimeline =
                    screen.toolTimeline.map { row ->
                        val card = row.card ?: return@map row
                        if (card.approvalId != approvalId) return@map row
                        row.copy(card = transform(card))
                    },
            )
        }
    }

    fun setCardStateForCall(
        callId: String,
        state: ApprovalCardState,
        terminalDetail: String?,
        keepDenied: Boolean = false,
    ) {
        screenState.update { screen ->
            screen.copy(
                toolTimeline =
                    screen.toolTimeline.map { row ->
                        val card = row.card ?: return@map row
                        // Scoped to THIS call's row: timeline rows keep their terminal
                        // cards (a denied card stays visible), so an unscoped update would
                        // relabel older calls' cards with this call's outcome.
                        if (row.callId != callId) return@map row
                        // A user-denied card stays DENIED — a later framework rejection of
                        // the same call must not relabel the user's own decision.
                        if (keepDenied && card.state == ApprovalCardState.DENIED) return@map row
                        row.copy(card = card.copy(state = state, terminalDetail = terminalDetail))
                    },
            )
        }
    }
}
