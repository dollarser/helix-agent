package com.helix.app.chat

import com.helix.app.approval.ApprovalCardUi

/**
 * One tool-related row of the session timeline (roadmap HXA-036; doc 01 FR-CHAT-003: the
 * timeline distinguishes model text, tool REQUEST, tool RESULT and the approval CARD).
 *
 * [card] is non-null only while the approval card is LIVE (pending / just decided, this
 * process): the full 13-field confirmation surface is a live display; persisted history
 * reconstructs the request + result + decision state (the profile-at-request-time is a
 * live fact of the confirmation surface, not a persisted column — HXA-036 boundary).
 * [requestSummary] is the FULL canonical argument JSON (doc 02 section 5.4: not
 * character-truncated for the current call); [resultSummary] is a bounded summary.
 */
data class ToolTimelineRow(
    val turnId: String,
    val callId: String,
    val toolName: String,
    val requestSummary: String,
    val stateLabel: String,
    val resultSummary: String?,
    val card: ApprovalCardUi?,
)
