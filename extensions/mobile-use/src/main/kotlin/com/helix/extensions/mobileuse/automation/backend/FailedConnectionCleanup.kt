package com.helix.extensions.mobileuse.automation.backend

/** Release a failed bootstrap without replacing its original cause or reporting successful execution. */
@Suppress("TooGenericExceptionCaught") // Cleanup errors are secondary; the original failure is always rethrown.
internal fun releaseFailedConnection(
    failure: Exception,
    release: () -> Unit,
): Nothing {
    try {
        release()
    } catch (cleanup: Exception) {
        if (cleanup !== failure) failure.addSuppressed(cleanup)
    }
    throw failure
}
