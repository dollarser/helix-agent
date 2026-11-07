package com.helix.feature.conversation.presentation

import com.helix.feature.conversation.presentation.RecoveryFacts
import com.helix.feature.conversation.presentation.RecoverySummary
import com.helix.feature.conversation.presentation.ToolCallFact
import com.helix.feature.conversation.presentation.recoverySummary

/**
 * HXA-204 slice 2: one settled turn's recovery panel as rendered in the chat conversation.
 * [summary] is slice 1's read-only projection of persisted facts; [retryAllowed] marks the
 * single turn that owns the new-call retry admission so the retry identity (testTag
 * `chat-retry`) stays unique across panels; [goalId]/[goalObjective] carry the bound Goal's
 * own continue identity (CONTINUE_GOAL is only offered when the Goal's own admission holds).
 */
data class TurnRecoveryPanelUi(
    val turnId: String,
    val turnState: String,
    val summary: RecoverySummary,
    val retryAllowed: Boolean,
    val goalId: String?,
    val goalObjective: String?,
    val inlineNoticeShown: Boolean = false,
    val stopFacts: TurnStopFacts? = null,
)

/** One turn's recovery facts plus its bound Goal identity, as gathered by [loadTurnRecoverySources]. */
data class TurnRecoverySource(
    val turnId: String,
    val goalId: String?,
    val goalObjective: String?,
    val facts: RecoveryFacts,
    /**
     * A later COMPLETED turn in the session has moved the conversation past this failure;
     * the existing `chat-retry` visibility rule keeps the retry button hidden in that case
     * (later CANCELLED / INTERRUPTED turns do NOT supersede).
     */
    val supersededByCompleted: Boolean = false,
    val stopFacts: TurnStopFacts? = null,
)

/**
 * Pure: maps [sources] to the panels the UI renders, keyed by turn id. A turn gets a panel
 * only when its projected summary is blocked; the retry operation belongs to the single
 * admitted [retryTargetTurnId] — and only while that failure has not been superseded by a
 * later successful result (the existing `chat-retry` visibility rule), never duplicated
 * across panels.
 */
fun recoveryPanelsFor(
    sources: List<TurnRecoverySource>,
    retryTargetTurnId: String?,
): Map<String, TurnRecoveryPanelUi> =
    sources
        .mapNotNull { source ->
            val summary = recoverySummary(source.facts)
            if (!summary.blocked) return@mapNotNull null
            TurnRecoveryPanelUi(
                turnId = source.turnId,
                turnState = source.facts.turnState,
                summary = summary,
                retryAllowed =
                    retryTargetTurnId != null &&
                        retryTargetTurnId == source.turnId &&
                        !source.supersededByCompleted,
                goalId = source.goalId,
                goalObjective = source.goalObjective,
                stopFacts = source.stopFacts,
            )
        }.associateBy { it.turnId }
