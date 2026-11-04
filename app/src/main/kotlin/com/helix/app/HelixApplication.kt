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

@Suppress("TooManyFunctions") // Application lifecycle and lazy service ownership stay in one boundary.
class HelixApplication : Application() {
    private val profileRestore by lazy {
        com.helix.app.backup
            .ProfileRestoreCoordinator(this)
    }
    private val profileInitialization by lazy {
        BackgroundInitialization("helix-profile-init") {
            profileRestore.recoverBeforeConsumers()
            com.helix.core.policy.network.NativeNetwork
                .initialize(filesDir)
        }
    }

    private fun awaitPortableProfile() {
        check(!Process.isIsolated() && getProcessName() == packageName)
        profileInitialization.await()
    }

    internal suspend fun awaitPortableProfileAsync() {
        check(!Process.isIsolated() && getProcessName() == packageName)
        profileInitialization.awaitAsync()
    }

    @Synchronized
    internal fun leaveReadAloudIfInitialized() {
        readAloudService?.leave()
    }

    val account by lazy {
        awaitPortableProfile()
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
        awaitPortableProfile()
        com.helix.app.voice
            .SpeechSettingsStore(this)
    }
    val speechConsent by lazy {
        awaitPortableProfile()
        com.helix.app.voice
            .SpeechConsentStore(this)
    }
    private var readAloudSettings: com.helix.app.voice.SpeechServiceSettings? = null
    private var readAloudIdentity: String? = null
    private var readAloudService: com.helix.app.voice.ReadAloudService? = null
    private val speechSynthesis = kotlinx.coroutines.sync.Mutex()

    suspend fun stopSpeechForInput() {
        val active = synchronized(this) { readAloudService }
        active?.stopAndAwait()
    }

    val readAloud: com.helix.app.voice.ReadAloudService
        @Synchronized get() {
            val settings = speechSettings.forAccount(account.status.value)
            if (settings != readAloudSettings || (settings.requiresAccount && readAloudIdentity != account.identity)) {
                readAloudService?.close()
                readAloudService = createReadAloud(settings)
                readAloudSettings = settings
                readAloudIdentity = account.identity
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

    val replyAudioSettings by lazy {
        com.helix.app.voice
            .ReplyAudioSettings(this)
    }

    private fun createReadAloud(settings: SpeechServiceSettings): ReadAloudService {
        val identity = account.identity
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
                        if (settings.useAccountLogin) check(account.signedIn.value && account.identity == identity)
                        settings.tts(account)
                    }
                } else {
                    null
                },
            uploadDestination = settings.ttsEndpoint.takeIf { settings.httpEnabled },
            segmentedHttp = settings.httpEnabled && !settings.webSocket,
            accountIdentity = identity.takeIf { settings.requiresAccount },
            synthesis = speechSynthesis,
        )
    }

    val reminders by lazy {
        awaitPortableProfile()
        com.helix.app.reminder.ReminderService(
            com.helix.core.storage.reminder
                .ReminderStore(this),
            com.helix.app.reminder
                .ReminderPlatform(this),
        )
    }
    val knowledge by lazy {
        awaitPortableProfile()
        com.helix.app.knowledge
            .KnowledgeService(this)
    }
    private val containerInitialization by lazy {
        check(!Process.isIsolated() && getProcessName() == packageName) {
            "AppContainer belongs to the main process"
        }
        AppInitialization<AppContainer>(
            factory = {
                com.helix.app.backup.ProfileReplacement.consumersStarted = true
                // Initialization runs off main. Restore local identity before model admission/UI rows.
                try {
                    kotlinx.coroutines.runBlocking { account.restore() }
                } catch (_: java.io.IOException) {
                    Log.w("HelixAccount", "Account restoration unavailable; built-in models remain locked")
                }
                DefaultAppContainer(this)
            },
            onReady = {
                val restoring =
                    com.helix.app.backup.ProfileReplacement
                        .pending(this)
                if (restoring) {
                    kotlinx.coroutines.runBlocking {
                        reminders.reconcile()
                        it.scheduledAgents?.reconcile()
                    }
                    recover(it, synchronous = true)
                }
                profileRestore.completeAfterConsumers()
                reminders.recoverAsync()
                com.helix.app.reminder.ReminderRecovery
                    .periodic(this)
                if (!restoring) recover(it)
            },
            onFailure = {
                profileRestore.markConsumerFailure()
                Log.e("HelixStartup", "Application initialization failed", it)
            },
            failureState = {
                if (com.helix.app.backup.ProfileReplacement
                        .pending(this) ||
                    com.helix.app.backup.ProfileReplacement.restoreAttempted
                ) {
                    AppStartupState.RestoreFailed
                } else {
                    AppStartupState.Failed
                }
            },
            beforeReset = {
                com.helix.app.goal.GoalReminderScheduler
                    .cancelForDatabaseReset(this)
            },
            preflight = {
                awaitPortableProfile()
                com.helix.app.maintenance
                    .previewStateGate(this)
                    .inspect()
            },
        )
    }

    /** Room initialization always runs off main, even when an Activity wins first access. */
    val appContainer: AppContainer
        get() = containerInitialization.await()

    suspend fun awaitAppContainer(): AppContainer = containerInitialization.awaitAsync()

    internal val startupState get() = containerInitialization.state

    internal fun confirmPreviewReset(request: com.helix.app.maintenance.PreviewResetRequest) =
        containerInitialization.confirmReset(request)

    internal fun recheckPreviewState() = containerInitialization.recheck()

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
        if (!Process.isIsolated() && getProcessName() != packageName) {
            check(
                !com.helix.app.backup.ProfileReplacement
                    .pending(this),
            ) { "RESTORE_RESTART_REQUIRED" }
            com.helix.core.policy.network.NativeNetwork
                .initialize(filesDir)
        }
        // Android also creates this Application in isolated QuickJS and private Runtime
        // processes. Only the main process owns host diagnostics, Room and recovery.
        if (Process.isIsolated() || getProcessName() != packageName) return
        com.helix.app.diagnostics.DiagnosticArchives
            .install(this)
        processDiagnostics = ProcessDiagnostics.install(this)
        // Schedule before recovery/UI access, preserving the private Runtime process guard.
        containerInitialization
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recover(
        container: AppContainer,
        synchronous: Boolean = false,
    ) {
        val action =
            Runnable {
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
                    if (synchronous) throw t
                }
            }
        if (synchronous) action.run() else Thread(action, RECOVERY_THREAD_NAME).start()
    }

    private companion object {
        const val TAG = "HelixRecovery"
        const val RECOVERY_THREAD_NAME = "helix-recovery"
    }
}
