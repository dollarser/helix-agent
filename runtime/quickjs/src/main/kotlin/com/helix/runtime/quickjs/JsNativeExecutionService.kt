package com.helix.runtime.quickjs

/** Explicit app-UID execution. Android permissions still apply; this is not a sandbox. */
class JsNativeExecutionService : JsExecutionService() {
    override val nativeAccess: Boolean = true

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Native calls can create threads: client death must not leave an orphan execution host.
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }
}
