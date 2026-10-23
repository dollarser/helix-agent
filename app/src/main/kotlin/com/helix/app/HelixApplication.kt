package com.helix.app

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.helix.app.diagnostics.ProcessDiagnostics
import com.helix.app.language.AppLanguageStore
import com.helix.app.voice.ReadAloudService
import com.helix.app.voice.SpeechServiceSettings
import com.helix.core.model.SystemClock

class HelixApplication : Application() {
    val account by lazy {
        com.helix.app.account.AccountSession(
            com.helix.app.account
                .AccountSecretStorage(
                    com.helix.core.storage.AndroidKeystoreSecretStore
                        .create(this),
                ),
        )
    }

    internal val runtimeRecoveryReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    val speechSettings by lazy {
        com.helix.app.voice
            .SpeechSettingsStore(this)
    }
    private var readAloudSettings: com.helix.app.voice.SpeechServiceSettings? = null
    private var readAloudService: com.helix.app.voice.ReadAloudService? = null
    private val speechSynthesis = kotlinx.coroutines.sync.Mutex()
    val readAloud: com.helix.app.voice.ReadAloudService
        @Synchronized get() {
            val settings = speechSettings.read()
            if (settings != readAloudSettings) {
                readAloudService?.close()
                readAloudService = createReadAloud(settings)
                readAloudSettings = settings
            }
            return requireNotNull(readAloudService)
        }

    val offlineAsr by lazy {
        com.helix.app.voice
            .OfflineAsrModel(
                com.helix.app.voice.LocalSpeechModel
                    .asr(this),
            )
    }

    private fun createReadAloud(settings: SpeechServiceSettings): ReadAloudService {
        val audio =
            com.helix.app.voice
                .SpeechAudio(this)
        return com.helix.app.voice.ReadAloudService(
            com.helix.app.voice
                .LocalSpeechModel(this),
            { directory ->
                com.helix.app.voice
                    .MeloSpeechEngine(directory)
            },
            audio::focus,
            audio::sink,
            cloudEngine =
                if (settings.httpEnabled) {
                    {
                        settings.validate()
                        settings.tts(account)
                    }
                } else {
                    null
                },
            uploadDestination = settings.ttsEndpoint.takeIf { settings.httpEnabled },
            segmentedHttp = settings.httpEnabled && !settings.webSocket,
            synthesis = speechSynthesis,
        )
    }

    val reminders by lazy {
        com.helix.app.reminder.ReminderService(
            com.helix.core.storage.reminder
                .ReminderStore(this),
            com.helix.app.reminder
                .ReminderPlatform(this),
        )
    }
    val knowledge by lazy {
        com.helix.app.knowledge
            .KnowledgeService(this)
    }
    private val containerInitialization by lazy {
        check(!Process.isIsolated() && getProcessName() == packageName) {
            "AppContainer belongs to the main process"
        }
        AppInitialization<AppContainer>(
            factory = {
                // Initialization runs off main. Restore local identity before model admission/UI rows.
                try {
                    kotlinx.coroutines.runBlocking { account.restore() }
                } catch (_: java.io.IOException) {
                    Log.w("HelixAccount", "Account restoration unavailable; built-in models remain locked")
                }
                DefaultAppContainer(this)
            },
            onReady = ::recover,
            onFailure = { Log.e("HelixStartup", "Application initialization failed", it) },
            beforeReset = {
                com.helix.app.goal.GoalReminderScheduler
                    .cancelForDatabaseReset(this)
            },
        )
    }

    /** Room initialization always runs off main, even when an Activity wins first access. */
    val appContainer: AppContainer
        get() = containerInitialization.await()

    suspend fun awaitAppContainer(): AppContainer = containerInitialization.awaitAsync()

    internal val startupState get() = containerInitialization.state

    internal fun confirmPreviewReset(request: com.helix.core.storage.PreviewDatabaseResetRequired) =
        containerInitialization.confirmReset(request)

    internal fun readyAppContainerOrNull(): AppContainer? = containerInitialization.readyOrNull()

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
     *
     * The broad catch is intentional (suppressed below): a startup maintenance failure — any
     * persistence, validation or mapping error — must not take the app down on every cold
     * start; it is logged in full and recovery is re-attempted at the next start, with the
     * persisted state consistent either way (doc 9.2). Genuine Errors are not swallowed.
     */
    override fun onCreate() {
        super.onCreate()
        if (!Process.isIsolated()) {
            com.helix.core.policy.network.NativeNetwork
                .initialize(filesDir)
        }
        // Android also creates this Application in isolated QuickJS and private Runtime
        // processes. Only the main process owns host diagnostics, Room and recovery.
        if (Process.isIsolated() || getProcessName() != packageName) return
        processDiagnostics = ProcessDiagnostics.install(this)
        reminders.recoverAsync()
        com.helix.app.reminder.ReminderRecovery
            .periodic(this)
        // Schedule before recovery/UI access, preserving the private Runtime process guard.
        containerInitialization
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recover(container: AppContainer) {
        Thread(
            {
                try {
                    requireNotNull(container.turnEngine) {
                        "main-process AppContainer must provide TurnEngine"
                    }.recoverOnStartup()
                    container.chatService.onRecoveryCompleted()
                    com.helix.app.goal
                        .GoalReminderReconciler(
                            container.storage,
                            com.helix.app.goal.GoalReminderScheduler
                                .create(this),
                            SystemClock(),
                        ).reconcileAll()
                    // Install the scheduled request guard before opening any background admission.
                    container.scheduledAgents
                    runtimeRecoveryReady.complete(Unit)
                    com.helix.app.scheduled.ScheduledAgentRecovery
                        .enqueue(this)
                    com.helix.app.scheduled.ScheduledAgentRecovery
                        .periodic(this)
                } catch (t: Exception) {
                    runtimeRecoveryReady.completeExceptionally(t)
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
