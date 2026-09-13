package com.helix.core.agent

import com.helix.core.model.GoalState

/**
 * Why a goal sits in [GoalState.PAUSED] — the foreground notification must show them
 * differently (research doc section 15 "Background / Always-on"): the user paused it, or Android
 * interrupted it. Process death / Doze / force-stop park a RUNNING goal in PAUSED (see the
 * GoalState doc); that is a SYSTEM pause, and the card must say so rather than claim background
 * autonomy. The coordinator records this when it parks a goal; it is only meaningful while the
 * state is PAUSED.
 */
enum class GoalPauseReason {
    /** The user tapped Pause on a running goal. */
    USER,

    /** Android interrupted it (process death, Doze, or force-stop). */
    SYSTEM,
}

/** A foreground / task-card action (research doc section 13 task card, section 15 notification). */
enum class GoalForegroundAction {
    /** Open the task detail. */
    OPEN,

    /** Pause a running goal (section 13/15 card). */
    PAUSE,

    /** Resume a paused goal; only this starts a new run (section 15). */
    RESUME,
}

/**
 * The pure foreground projection for a goal (research doc sections 13 and 15): the honest,
 * bounded-foreground card — a status line, an optional "Step X/Y" progress line, and the actions
 * to offer. It never promises 24/7 background: a goal Android parked is "Paused by Android" with
 * [GoalForegroundAction.RESUME], and only an explicit resume starts a new run. [GoalForeground]
 * reads the pause reason only for [GoalState.PAUSED].
 */
data class GoalForegroundCard(
    val statusLine: String,
    val progressLine: String?,
    val actions: List<GoalForegroundAction>,
)

/** The single source of truth for what a goal's foreground card/notification shows and offers. */
object GoalForeground {
    /**
     * Projects [state] (and [pauseReason] when PAUSED) into a card. [step]/[totalSteps] yield the
     * progress line when both are present with a positive total; [pauseReason] is ignored for
     * non-PAUSED states.
     */
    fun card(
        state: GoalState,
        pauseReason: GoalPauseReason?,
        step: Int? = null,
        totalSteps: Int? = null,
    ): GoalForegroundCard =
        GoalForegroundCard(
            statusLine = statusLine(state, pauseReason),
            progressLine = progressLine(step, totalSteps),
            actions = actions(state),
        )

    private fun statusLine(
        state: GoalState,
        pauseReason: GoalPauseReason?,
    ): String =
        when (state) {
            GoalState.DRAFT -> {
                "Draft"
            }

            GoalState.READY -> {
                "Ready"
            }

            GoalState.RUNNING -> {
                "Working"
            }

            GoalState.INPUT_REQUIRED -> {
                "Needs input"
            }

            GoalState.PAUSED -> {
                if (pauseReason == GoalPauseReason.SYSTEM) "Paused by Android" else "Paused"
            }

            GoalState.BLOCKED -> {
                "Blocked"
            }

            GoalState.COMPLETED -> {
                "Completed"
            }

            GoalState.FAILED -> {
                "Failed"
            }

            GoalState.CANCELLED -> {
                "Cancelled"
            }
        }

    /** A running goal offers Pause; a paused goal offers Resume; everything else only Open. */
    private fun actions(state: GoalState): List<GoalForegroundAction> =
        when (state) {
            GoalState.RUNNING -> listOf(GoalForegroundAction.OPEN, GoalForegroundAction.PAUSE)
            GoalState.PAUSED -> listOf(GoalForegroundAction.OPEN, GoalForegroundAction.RESUME)
            else -> listOf(GoalForegroundAction.OPEN)
        }

    private fun progressLine(
        step: Int?,
        totalSteps: Int?,
    ): String? =
        if (step != null && totalSteps != null && totalSteps > 0) {
            "Step $step/$totalSteps"
        } else {
            null
        }
}
