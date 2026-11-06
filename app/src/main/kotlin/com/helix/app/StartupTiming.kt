package com.helix.app

private const val STARTUP_TIMING_TAG = "HelixStartupTiming"

/** Opt-in phase timing: no account, prompt or file content is recorded. */
internal inline fun <T> startupPhase(
    name: String,
    action: () -> T,
): T {
    val started = android.os.SystemClock.elapsedRealtime()
    try {
        return action()
    } finally {
        if (android.util.Log.isLoggable(
                STARTUP_TIMING_TAG,
                android.util.Log.DEBUG,
            )
        ) {
            android.util.Log.d(
                STARTUP_TIMING_TAG,
                "$name: ${android.os.SystemClock.elapsedRealtime() - started} ms",
            )
        }
    }
}
