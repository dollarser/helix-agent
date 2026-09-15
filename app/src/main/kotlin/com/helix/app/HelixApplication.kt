package com.helix.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.helix.app.diagnostics.ProcessDiagnostics
import com.helix.app.language.AppLanguageStore
import com.helix.app.recovery.RecoveryCoordinatorApp
import com.helix.core.model.SystemClock
import java.util.concurrent.CountDownLatch

class HelixApplication : Application() {
    private val containerInitLock = Any()
    private var containerInstance: AppContainer? = null
    private var containerInitError: Throwable? = null
    private var containerInitScheduled = false
    private val containerReady = CountDownLatch(1)

    /**
     * The app container. Construction performs blocking Room work (database open, the
     * provider-registration writes), which must never run on the main thread: with a plain
     * `by lazy`, a cold start let [MainActivity.onCreate]'s first access win the race
     * against the recovery thread and initialize on main (Room ISE crash, device-verified
     * in the browser activity-lifecycle test). The FIRST init therefore runs on a dedicated
     * background thread started in [onCreate], and every access — including the main
     * thread — blocks until it completes. In an isolated process (where [onCreate] does not
     * schedule that thread) the legacy synchronous lazy behavior is preserved.
     */
    val appContainer: AppContainer
        get() {
            if (!ensureContainerInitialized()) {
                containerReady.await()
            }
            val instance = containerInstance
            if (instance != null) return instance
            val failure = containerInitError
            if (failure != null) throw failure
            error("app container unavailable")
        }

    /**
     * True when the container is initialized after this call (already set, or initialized
     * synchronously in an isolated process); false when the process scheduled the background
     * init and the caller must wait on [containerReady].
     */
    private fun ensureContainerInitialized(): Boolean {
        containerInstance?.let { return true }
        return synchronized(containerInitLock) {
            containerInstance?.let { true } ?: if (containerInitScheduled) {
                false
            } else {
                containerInitScheduled = true
                initContainer()
                true
            }
        }
    }

    /**
     * The broad catch is intentional (suppressed below): the init runs on a background
     * thread, so a construction failure — any persistence, validation or mapping error —
     * must be recorded, not crash the process; the next [appContainer] read re-throws it,
     * and [containerReady] always releases so a failed init can never leave a caller
     * blocked.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun initContainer() {
        try {
            containerInstance = DefaultAppContainer(this)
        } catch (t: Throwable) {
            containerInitError = t
        } finally {
            containerReady.countDown()
        }
    }

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
        // Android also creates this Application in QuickJS's isolated UID. It must not
        // read host preferences/Room or start host diagnostics and recovery there.
        if (Process.isIsolated()) return
        processDiagnostics = ProcessDiagnostics.install(this)
        // Pin the container's first (and only) init to a background thread: every other
        // thread — main included — then blocks on the latch in the [appContainer] getter
        // instead of risking a main-thread initialization (Room ISE on cold start).
        synchronized(containerInitLock) {
            containerInitScheduled = true
        }
        Thread(this::initContainer, CONTAINER_INIT_THREAD_NAME).start()
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
        const val CONTAINER_INIT_THREAD_NAME = "helix-container-init"
    }
}
