package com.helix.app.application.agent

/** A task-scoped display fact; it neither authorizes replay nor changes execution outcomes. */
enum class TurnInterruptionNotice {
    INTERRUPTED,
    UNKNOWN_EFFECTS,
    INSPECTION_UNAVAILABLE,
    ;

    companion object {
        const val KIND = "TURN_INTERRUPTION_NOTICE"

        fun idFor(turnId: String): String = "recovery-notice:$turnId"

        fun forDisplay(
            saved: TurnInterruptionNotice?,
            state: String,
            userStopped: Boolean,
            unresolvedEffects: Boolean,
            inspection: Boolean,
        ): TurnInterruptionNotice? =
            when {
                userStopped || inspection || state in setOf("COMPLETED", "CANCELLED") -> null
                saved == null && state !in setOf("INTERRUPTED", "NEEDS_REVIEW") -> null
                unresolvedEffects -> UNKNOWN_EFFECTS
                saved == INSPECTION_UNAVAILABLE -> INSPECTION_UNAVAILABLE
                else -> INTERRUPTED
            }

        fun classify(
            userStopped: Boolean,
            unresolvedEffects: Boolean,
            inspectionFailed: Boolean,
        ): TurnInterruptionNotice? =
            when {
                userStopped -> null
                unresolvedEffects -> UNKNOWN_EFFECTS
                inspectionFailed -> INSPECTION_UNAVAILABLE
                else -> INTERRUPTED
            }
    }
}
