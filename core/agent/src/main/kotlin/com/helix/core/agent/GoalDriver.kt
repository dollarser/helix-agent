package com.helix.core.agent

import com.helix.core.model.GoalState

/**
 * Result of one [GoalDriver.admit] pre-flight (HX2-08, research doc section 32).
 *
 * The driver answers the *admission* question — "should we attempt to start a run right now?" —
 * layered above the reducer's *capability* question — "can a run start?". On [Admitted] the
 * caller fires [GoalEvent.Continued] with the same [GoalWakeReason]; the reducer re-checks
 * state and budget and emits the [GoalEffect.StartRun] effect. The driver never mutates goal
 * state itself — it is pure policy over the current [Goal], so it cannot desync from the
 * reducer the way a second, I/O-owning state machine would.
 */
sealed interface GoalRunAdmission {
    /**
     * The goal may start a new run from [source]. Fire [GoalEvent.Continued] with [source];
     * the reducer re-applies its state/budget checks and produces [GoalEffect.StartRun].
     */
    data class Admitted(
        val source: GoalWakeReason,
    ) : GoalRunAdmission

    /**
     * The goal must not start a run from [source]. No state change; surface [reason] (and
     * [detail] for diagnostics) instead of firing a wake.
     */
    data class Rejected(
        val reason: GoalRejectionReason,
        val detail: String,
    ) : GoalRunAdmission
}

/** Why the [GoalDriver] refused to admit a wake. */
enum class GoalRejectionReason {
    /** The goal is COMPLETED/FAILED/CANCELLED and can never run again. */
    GOAL_TERMINAL,

    /** [GoalWakeReason] is not permitted by the active [GoalWakePolicy] (v1 gates ambient wakes). */
    SOURCE_NOT_PERMITTED,

    /** The goal state cannot accept a [GoalEvent.Continued] (DRAFT, RUNNING, BLOCKED). */
    STATE_NOT_ADMITTABLE,

    /** The goal budget has no headroom for another run ([Goal.hasRunBudgetHeadroom] is false). */
    BUDGET_EXHAUSTED,
}

/**
 * Which wake sources the [GoalDriver] is willing to act on (research doc section 32: the driver,
 * not the reducer, owns the "should we attempt one?" policy, so future Cron/Channel/A2A/push
 * sources are enabled here without the Goal reducer ever special-casing them).
 *
 * [V1] admits only the two explicit user wakes — exactly the current product behavior, where a
 * goal advances only when the user opens it or taps its notification. Ambient sources
 * (foreground continuation, scheduled checkpoint, channel event) stay gated until their product
 * loops land; they are rejected with [GoalRejectionReason.SOURCE_NOT_PERMITTED], independent of
 * the goal's own state, so unlocking one is a policy change, not a reducer change.
 */
data class GoalWakePolicy(
    val permittedSources: Set<GoalWakeReason>,
) {
    companion object {
        val V1: GoalWakePolicy =
            GoalWakePolicy(
                setOf(GoalWakeReason.USER_OPEN, GoalWakeReason.NOTIFICATION_ACTION),
            )
    }
}

/**
 * Pre-flight admission for a Goal wake (HX2-08). A pure function of `(goal, source, policy)` —
 * no I/O, no state mutation, no coroutines. The app-layer runtime (which loads the goal from
 * storage and, on [GoalRunAdmission.Admitted], fires [GoalEvent.Continued]) is where the
 * `suspend`/I/O wrapper the research doc sketches belongs.
 *
 * Decision order (each short-circuits with a distinct [GoalRejectionReason]):
 * 1. terminal goal       -> [GoalRejectionReason.GOAL_TERMINAL]
 * 2. source not permitted -> [GoalRejectionReason.SOURCE_NOT_PERMITTED] (independent of state)
 * 3. state not resumable -> [GoalRejectionReason.STATE_NOT_ADMITTABLE]
 * 4. no budget headroom  -> [GoalRejectionReason.BUDGET_EXHAUSTED]
 * otherwise              -> [GoalRunAdmission.Admitted]
 *
 * Steps 3 and 4 mirror the reducer's [GoalEvent.Continued] acceptance (READY/PAUSED/
 * INPUT_REQUIRED plus [Goal.hasRunBudgetHeadroom]), which the reducer shares via that same
 * predicate. Consequently a [GoalRunAdmission.Admitted] is *always* accepted by the reducer:
 * the driver is a strict pre-filter, never a second source of truth.
 */
object GoalDriver {
    private val RESUMABLE_STATES: Set<GoalState> =
        setOf(GoalState.READY, GoalState.PAUSED, GoalState.INPUT_REQUIRED)

    @Suppress("ReturnCount") // Sequential admission gates, one early return each.
    fun admit(
        goal: Goal,
        source: GoalWakeReason,
        policy: GoalWakePolicy = GoalWakePolicy.V1,
    ): GoalRunAdmission {
        if (goal.isTerminal) {
            return GoalRunAdmission.Rejected(
                GoalRejectionReason.GOAL_TERMINAL,
                "goal ${goal.id} is ${goal.state} and can no longer be woken",
            )
        }
        if (source !in policy.permittedSources) {
            return GoalRunAdmission.Rejected(
                GoalRejectionReason.SOURCE_NOT_PERMITTED,
                "wake source $source is not admitted by the active policy (state ${goal.state})",
            )
        }
        if (goal.state !in RESUMABLE_STATES) {
            return GoalRunAdmission.Rejected(
                GoalRejectionReason.STATE_NOT_ADMITTABLE,
                "goal state ${goal.state} cannot accept a wake (only READY/PAUSED/INPUT_REQUIRED can)",
            )
        }
        if (!goal.hasRunBudgetHeadroom()) {
            return GoalRunAdmission.Rejected(
                GoalRejectionReason.BUDGET_EXHAUSTED,
                "goal ${goal.id} has no budget headroom for another run",
            )
        }
        return GoalRunAdmission.Admitted(source)
    }
}
