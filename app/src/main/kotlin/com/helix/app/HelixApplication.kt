package com.helix.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.helix.app.diagnostics.ProcessDiagnostics
import com.helix.app.language.AppLanguageStore
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.model.SystemClock

class HelixApplication : Application() {
    private val containerInitialization by lazy {
        check(!Process.isIsolated() && getProcessName() == packageName) {
            "AppContainer belongs to the main process"
        }
        BackgroundInitialization("helix-container-init") { DefaultAppContainer(this) }
    }

    /** Room initialization always runs off main, even when an Activity wins first access. */
    val appContainer: AppContainer
        get() = containerInitialization.await()

    private lateinit var processDiagnostics: ProcessDiagnostics

    /**
     * HXA-069: apply the app UI language to the application context (and thus every service and
     * resource built from it) before any resource is read. [AppLanguageStore.effectiveLocaleList]
     * is fail-closed — a read error degrades to the system default, never to an empty/invalid
     * locale.
     */
    override fun attachBaseContext(base: Context) {
        if (Process.isIsolated()) {
            super.attachBaseContext(base)
            return
        }
        super.attachBaseContext(
            AppLanguageStore.wrapForLocale(base, AppLanguageStore.effectiveLocaleList(base)),
        )
    }

    /**
     * Process-restart recovery (HXA-015). The storage-backed coordinator marks leftover active
     * Turns INTERRUPTED and parks RUNNING goals; it is a no-op on an already-recovered
     * database. It runs on a background thread because Room queries are not allowed on the
     * main thread. HXA-028: the coordinator shares the container's HelixStorage (one database
     * connection per process) instead of opening a second connection.
     */
    private val recoveryCoordinator: RecoveryCoordinatorApp by lazy {
        RecoveryCoordinatorApp(appContainer.storage, SystemClock())
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
        // Android also creates this Application in isolated QuickJS and private Runtime
        // processes. Only the main process owns host diagnostics, Room and recovery.
        if (Process.isIsolated() || getProcessName() != packageName) return
        processDiagnostics = ProcessDiagnostics.install(this)
        // Schedule before recovery/UI access, preserving the private Runtime process guard.
        containerInitialization
        Thread(
            {
                try {
                    recoveryCoordinator.recover()
                    appContainer.chatService.onRecoveryCompleted()
                    com.helix.app.goal
                        .GoalReminderReconciler(
                            appContainer.storage,
                            com.helix.app.goal.GoalReminderScheduler
                                .create(this),
                            SystemClock(),
                        ).reconcileAll()
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
