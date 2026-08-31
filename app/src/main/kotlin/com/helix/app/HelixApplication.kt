package com.helix.app

import android.app.Application
import android.util.Log
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.model.SystemClock
import com.helix.core.storage.HelixStorage

class HelixApplication : Application() {
    val appContainer: AppContainer by lazy(::DefaultAppContainer)

    /**
     * Process-restart recovery (HXA-015). The storage-backed coordinator marks leftover active
     * Turns INTERRUPTED and parks RUNNING goals; it is a no-op on an already-recovered
     * database. It runs on a background thread because Room queries are not allowed on the
     * main thread.
     */
    private val recoveryCoordinator: RecoveryCoordinatorApp by lazy {
        RecoveryCoordinatorApp(HelixStorage.create(this), SystemClock())
    }

    /**
     * The broad catch is intentional (suppressed below): a startup maintenance failure — any
     * persistence, validation or mapping error — must not take the app down on every cold
     * start; it is logged in full and recovery is re-attempted at the next start, with the
     * persisted state consistent either way (doc 9.2). Genuine Errors are not swallowed.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun onCreate() {
        super.onCreate()
        Thread(
            {
                try {
                    recoveryCoordinator.recover()
                } catch (t: Exception) {
                    Log.e(TAG, "process recovery failed; will retry at next start", t)
                }
            },
            RECOVERY_THREAD_NAME,
        ).start()
    }

    private companion object {
        const val TAG = "HelixRecovery"
        const val RECOVERY_THREAD_NAME = "helix-recovery"
    }
}
