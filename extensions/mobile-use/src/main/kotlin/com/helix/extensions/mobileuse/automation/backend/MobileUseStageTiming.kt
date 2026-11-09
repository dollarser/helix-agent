package com.helix.extensions.mobileuse.automation.backend

/** Opt-in local diagnostics. Never records pixels, text, coordinates, tokens or credentials. */
internal inline fun <T> mobileUseStageTiming(
    stage: String,
    operation: () -> T,
): T {
    if (!android.util.Log.isLoggable("HelixMobileTiming", android.util.Log.DEBUG)) return operation()
    val started = System.nanoTime()
    var completed = false
    try {
        return operation().also { completed = true }
    } finally {
        android.util.Log.d(
            "HelixMobileTiming",
            "stage=$stage elapsedNanos=${System.nanoTime() - started} completed=$completed",
        )
    }
}
