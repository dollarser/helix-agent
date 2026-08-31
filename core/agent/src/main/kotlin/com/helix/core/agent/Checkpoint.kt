package com.helix.core.agent

/**
 * When the goal expects its next checkpoint (modes doc section 6.1). The coordinator schedules
 * a deferrable WorkManager reminder near this time; Doze, force-stop and system scheduling may
 * delay or cancel the reminder, so the UI must never present a checkpoint as an exact timer.
 * The checkpoint itself is data on the goal, not a system alarm.
 */
data class Checkpoint(
    val atEpochMillis: Long,
) {
    init {
        require(atEpochMillis >= 0) { "atEpochMillis must be >= 0" }
    }
}
