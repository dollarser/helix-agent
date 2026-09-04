package com.helix.app.foreground

import com.helix.core.model.TurnState

/**
 * The Android seam the pure-JVM [DataSyncForegroundController] drives. Production is
 * [AndroidForegroundServiceLauncher]; tests record the calls to pin the start/stop decision.
 */
interface ForegroundServiceLauncher {
    /** Bring the dataSync foreground service up (idempotent). */
    fun start()

    /** Tear the dataSync foreground service down (idempotent). */
    fun stop()
}

/**
 * Decides when a user-initiated Provider/MCP transport (or local file processing) runs as a
 * `dataSync` foreground service (roadmap HXA-066, architecture doc 5.1): ONLY while a turn is
 * actively moving data — building context, waiting on / receiving the model, running a tool.
 * The moment the turn waits for the user ([TurnState.WAITING_APPROVAL]) or goes idle (terminal,
 * or no active turn) the foreground service stops, so it is never a background residency.
 *
 * The decision is pure JVM — a [TurnState] in, a [ForegroundServiceLauncher] side-effect — so it
 * is host-unit-testable; the real Android start/stop lives in the launcher.
 */
class DataSyncForegroundController(
    private val launcher: ForegroundServiceLauncher,
) {
    private var foreground = false

    /**
     * Driven from the chat-screen collector (main dispatcher); guarded anyway because a StateFlow
     * replay and a live emit can interleave across a configuration change.
     */
    @Synchronized
    fun onTurnState(state: TurnState?) {
        val wantForeground = state != null && state in TRANSPORT_ACTIVE
        if (wantForeground && !foreground) {
            launcher.start()
            foreground = true
        } else if (!wantForeground && foreground) {
            launcher.stop()
            foreground = false
        }
    }

    companion object {
        /**
         * The turn phases that move the user's data on the wire. Deliberately excludes
         * [TurnState.WAITING_APPROVAL] (waiting for the user), [TurnState.CREATED] (no transport
         * yet), [TurnState.CANCELLING] / [TurnState.INTERRUPTED] (not advancing) and every terminal
         * state (idle, awaiting the next user input).
         */
        val TRANSPORT_ACTIVE: Set<TurnState> =
            setOf(
                TurnState.BUILDING_CONTEXT,
                TurnState.WAITING_MODEL,
                TurnState.RECEIVING_MODEL,
                TurnState.RUNNING_TOOL,
                TurnState.RECORDING_TOOL_RESULT,
            )
    }
}

/**
 * The dataSync foreground-service lifetime limit, as a pure predicate (roadmap HXA-066 "6 小时 /
 * 24 小时限额测试"). Android 15 (API 35) bounds a `dataSync` foreground service to 6 hours and
 * then invokes [android.app.Service.onTimeout]; [shouldStop] is the host-testable predicate the
 * service recognizes that bound with. A caller may pass a longer cap (e.g. [CEILING_LIMIT_MS]) to
 * test the same boundary at a different scale.
 */
object DataSyncLimitPolicy {
    /** The Android 15 `dataSync` foreground-service limit (6 hours). */
    const val DATA_SYNC_LIMIT_MS: Long = 6L * 60 * 60 * 1000

    /** The longer cap exercised by the limit tests (same predicate, a different scale). */
    const val CEILING_LIMIT_MS: Long = 24L * 60 * 60 * 1000

    /** Whether a service that started at [startedAtMs] has reached [limitMs] by [nowMs]. */
    fun shouldStop(
        startedAtMs: Long,
        nowMs: Long,
        limitMs: Long = DATA_SYNC_LIMIT_MS,
    ): Boolean = nowMs - startedAtMs >= limitMs
}
