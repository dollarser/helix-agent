package com.helix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.helix.app.allfiles.AllFilesModule
import com.helix.app.language.AppLanguageStore
import com.helix.app.root.RootModule
import com.helix.app.ui.AppAgentDefaultsScreen
import com.helix.app.ui.AppConversationRoute
import com.helix.app.ui.ArtifactsScreenDestination
import com.helix.app.ui.AuditScreen
import com.helix.app.ui.COMMAND_DETAIL_ROUTE
import com.helix.app.ui.CONVERSATION_HISTORY_ROUTE
import com.helix.app.ui.CONVERSATION_SEARCH_ROUTE
import com.helix.app.ui.CONVERSATION_SETTINGS_ROUTE
import com.helix.app.ui.CapabilitiesScreenDestination
import com.helix.app.ui.CapabilityReadinessScreen
import com.helix.app.ui.CommandResultDetailScreen
import com.helix.app.ui.CompactPageHeader
import com.helix.app.ui.ConversationDrawerState
import com.helix.app.ui.EXPERTS_ROUTE
import com.helix.app.ui.ExtensionsScreen
import com.helix.app.ui.FilesScreen
import com.helix.app.ui.FirstLaunchNoticeScreen
import com.helix.app.ui.GitStatusScreenDestination
import com.helix.app.ui.GroupedNavigation
import com.helix.app.ui.ModelsConnectionsScreen
import com.helix.app.ui.PermissionsSafetyScreen
import com.helix.app.ui.RuntimeSetupScreen
import com.helix.app.ui.SETTINGS_AUDIT_ROUTE
import com.helix.app.ui.SETTINGS_DEFAULTS_ROUTE
import com.helix.app.ui.SETTINGS_PERMISSIONS_ROUTE
import com.helix.app.ui.SETTINGS_STORAGE_ROUTE
import com.helix.app.ui.SETTINGS_SYSTEM_PERMISSIONS_ROUTE
import com.helix.app.ui.SETUP_CAPABILITIES_ROUTE
import com.helix.app.ui.SETUP_READINESS_ROUTE
import com.helix.app.ui.SETUP_RUNTIME_ROUTE
import com.helix.app.ui.SessionSettingsScreen
import com.helix.app.ui.SettingsScreen
import com.helix.app.ui.StorageUsageScreen
import com.helix.app.ui.TASKS_TURN_ROUTE
import com.helix.app.ui.TasksScreen
import com.helix.app.ui.commandDetailRoute
import com.helix.app.ui.completionFor
import com.helix.app.ui.configurePluginFrom
import com.helix.app.ui.mobileUseSettingsDestination
import com.helix.app.ui.navigateFrom
import com.helix.app.ui.openSection
import com.helix.app.ui.openSectionFrom
import com.helix.app.ui.pluginConfigurationDestination
import com.helix.app.ui.returnFrom
import com.helix.app.ui.secondaryRouteTitle
import com.helix.app.ui.tasksTurnRoute
import com.helix.core.model.ProviderProvisioningKind
import com.helix.feature.browser.BrowserViewOwner
import com.helix.feature.browser.ui.BrowserScreen
import com.helix.feature.conversation.application.SessionHistoryActions
import com.helix.feature.conversation.presentation.ConversationHistoryScreen
import com.helix.ui.shared.HelixTheme
import com.helix.ui.shared.HelixUiDesign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions") // Android lifecycle callbacks plus this Activity's startup/intent handling.
class MainActivity : ComponentActivity() {
    private var consumedReminderId: String? = null
    private var shareIntentConsumed = false

    /**
     * HXA-069: apply the app UI language to this activity's context (and every Composable
     * resource it reads) at attach time, via the platform [Context.createConfigurationContext]
     * (see [AppLanguageStore.wrapForLocale]). [AppLanguageStore.effectiveLocaleList] is
     * fail-closed — a read error degrades to the system default, never to an empty/invalid locale.
     */
    override fun attachBaseContext(base: Context) {
        val localeList = AppLanguageStore.effectiveLocaleList(base)
        val wrapped = AppLanguageStore.wrapForLocale(base, localeList)
        super.attachBaseContext(wrapped)
    }

    private var browserOwner: BrowserViewOwner? = null
    private var readyContainer by mutableStateOf<AppContainer?>(null)
    private var contentReady by mutableStateOf(false)
    private var contentInitialization: kotlinx.coroutines.Job? = null
    private var startupFailed by mutableStateOf(false)
    private val pendingIntents = mutableListOf<Intent>()
    private var startupIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumedReminderId = savedInstanceState?.getString("consumed_goal_reminder")
        shareIntentConsumed = savedInstanceState?.getBoolean("share_intent_consumed") ?: false
        @Suppress("DEPRECATION")
        val initialIntent = savedInstanceState?.getParcelable<Intent>("startup_initial_intent") ?: intent

        @Suppress("DEPRECATION")
        val restoredIntents = savedInstanceState?.getParcelableArrayList<Intent>("startup_pending_intents")
        pendingIntents.addAll(restoredIntents.orEmpty())
        startupIntent = initialIntent
        val app = application as HelixApplication
        val queuedRestore =
            synchronized(com.helix.app.backup.ProfileReplacement.lock) {
                com.helix.app.backup.ProfileReplacement
                    .pending(app) && app.readyAppContainerOrNull() != null
            }
        app.refreshStartupOnboarding()
        setContent {
            val onboarding by app.startupOnboarding.collectAsStateWithLifecycle()
            val startup by app.startupState.collectAsStateWithLifecycle()
            val visible =
                if (queuedRestore) {
                    AppStartupState.RestoreFailed
                } else if (startupFailed) {
                    AppStartupState.Failed
                } else {
                    startup
                }
            StartupExperience(
                visible,
                onboarding,
                contentReady,
                onNotice = app::acceptStartupNotice,
                onPermissionsDone = app::finishStartupPermissions,
                content = { readyContainer?.let { HelixApp(it) } },
                onConfirmReset = app::confirmPreviewReset,
                onExit = {
                    finishAndRemoveTask()
                    if (visible ==
                        AppStartupState.RestoreFailed
                    ) {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                },
                onRecheck = {
                    if (startupFailed) {
                        startupFailed = false
                        contentInitialization?.cancel()
                        contentInitialization = lifecycleScope.launch { initializeContent(initialIntent) }
                    } else {
                        app.recheckPreviewState()
                    }
                },
            )
        }
        if (!queuedRestore) contentInitialization = lifecycleScope.launch { initializeContent(initialIntent) }
    }

    @Suppress("TooGenericExceptionCaught") // Show startup failure; cancellation must not attach a destroyed Activity.
    private suspend fun initializeContent(initialIntent: Intent) {
        try {
            val app = application as HelixApplication
            app.startupState.first { it == AppStartupState.Ready }
            val container = app.awaitAppContainer()
            startupPhase("browser-ready") { container.browser.sessionReady.await() }
            val owner = BrowserViewOwner(this)
            browserOwner = owner
            readyContainer = container
            container.browser.attach(owner)
            app.startupOnboarding.first { it == StartupOnboarding.COMPLETE }
            acceptLaunchIntent(container, initialIntent, restore = true)
            pendingIntents.forEach {
                consumedReminderId = null
                acceptLaunchIntent(container, it, restore = false)
            }
            pendingIntents.clear()
            startupIntent = null
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                container.browser.resume(owner)
                container.providerService.refresh()
            }
            contentReady = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            browserOwner?.let { readyContainer?.browser?.detach(it) }
            browserOwner = null
            readyContainer = null
            startupFailed = true
            Log.e("HelixStartup", "Application initialization failed", failure)
        }
    }

    private fun acceptLaunchIntent(
        container: AppContainer,
        request: Intent,
        restore: Boolean,
    ) {
        val automation =
            if (request.action == Intent.ACTION_VIEW) {
                request.dataString?.let(com.helix.app.scheduled.ScheduledOpenRequest::parse)
            } else {
                null
            }
        automation?.let { container.scheduledAgents?.openRequest?.value = it }
        val isShare = request.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
        com.helix.app.reminder.localReminderId(request)?.let {
            (application as HelixApplication).reminders.openRequest.value = it
        }
        if (isShare && !(restore && shareIntentConsumed)) {
            (application as HelixApplication).knowledge.capture(request, callingPackage ?: "Android share")
            shareIntentConsumed = true
        }
        val goalId =
            com.helix.app.goal
                .goalReminderId(request)
        if (goalId != null && goalId != consumedReminderId) {
            consumedReminderId = goalId
            container.chatService.openGoalReminder(goalId)
        }
        val ordinaryLaunch = !isShare && goalId == null && automation == null
        if (restore && ordinaryLaunch) {
            container.chatService.restoreConversationLaunchTarget()
        }
    }

    // Best-effort WebView pause on background; onPause does not pause JavaScript globally.
    override fun onPause() {
        super.onPause()
        browserOwner?.let { readyContainer?.browser?.pause(it) }
    }

    override fun onStop() {
        (application as HelixApplication).leaveReadAloudIfInitialized()
        // A Root manager may revoke policy without killing an existing libsu shell. Never retain
        // developer Root authority after the app leaves the foreground; REQUESTING is preserved
        // so the manager's grant dialog can complete normally.
        RootModule.onAppBackgrounded()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        readyContainer?.browser?.trimMemory(level)
    }

    override fun onResume() {
        super.onResume()
        if (com.helix.app.backup.ProfileReplacement
                .pending(this)
        ) {
            return
        }
        if (readyContainer != null) (application as HelixApplication).reminders.recoverAsync()
        browserOwner?.let { readyContainer?.browser?.resume(it) }
        readyContainer?.providerService?.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = readyContainer
        if (container == null || !contentReady) {
            pendingIntents += intent
        } else {
            consumedReminderId = null
            acceptLaunchIntent(container, intent, restore = false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("consumed_goal_reminder", consumedReminderId)
        outState.putBoolean("share_intent_consumed", shareIntentConsumed)
        startupIntent?.let { outState.putParcelable("startup_initial_intent", it) }
        outState.putParcelableArrayList("startup_pending_intents", ArrayList(pendingIntents))
        super.onSaveInstanceState(outState)
    }

    /**
     * The tab STATE (app-scoped [BrowserController]) survives activity recreation, but its
     * WebViews are UI resources: destroy them with the activity. A tab whose host was just
     * destroyed renders its placeholder until the next navigation rebuilds the host lazily.
     */
    override fun onDestroy() {
        super.onDestroy()
        browserOwner?.let { readyContainer?.browser?.detach(it) }
        browserOwner = null
        readyContainer = null
    }
}

/**
 * The app shell (HXA-028): the first-launch privacy notice gates the whole UI
 * (ADR-0006: fresh install / reset → the notice + STANDARD), then the standard
 * drawer + NavHost shell. The routes that exist in M2 get real screens
 * (sessions = chat, settings = profile + providers); the not-yet-milestoned
 * destinations keep their honest empty states. ADR-0006: the UI shows only the
 * product name “Helix” — never a distribution/edition label.
 */
private fun drawerDirection(direction: androidx.compose.ui.unit.LayoutDirection): Int =
    if (direction == androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1

@OptIn(ExperimentalMaterial3Api::class)
// The Compose UI DSL keeps this screen intentionally in one composable; detekt's LongMethod
// threshold does not model UI composition well, so it is suppressed here only.
@Suppress("FunctionName", "LongMethod")
@Composable
internal fun HelixApp(container: AppContainer) {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as HelixApplication
    val repository = container.shellRepository
    val navController = rememberNavController()
    val mobileUseDialog =
        androidx.compose.runtime.saveable.rememberSaveable(
            saver = com.helix.app.ui.MobileUseDialogState.Saver,
        ) {
            com.helix.app.ui
                .MobileUseDialogState()
        }
    val departure =
        remember {
            com.helix.app.ui
                .NavigationDeparture(mobileUseDialog::dismiss)
        }
    com.helix.app.ui
        .DevicePageNavigation(navController, departure)
    com.helix.app.reminder.ReminderNavigation(
        (androidx.compose.ui.platform.LocalContext.current.applicationContext as HelixApplication).reminders,
        navController,
        departure,
    )
    com.helix.app.scheduled
        .ScheduledNavigation(container.scheduledAgents, navController, departure)
    val knowledge = (androidx.compose.ui.platform.LocalContext.current.applicationContext as HelixApplication).knowledge
    var citationUrl by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    var metricsMessageId by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf<String?>(null) }
    com.helix.app.knowledge
        .KnowledgeNavigation(knowledge, navController, departure)
    com.helix.app.plugin.PluginTaskNavigation
        .Observe(navController, departure)
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val direction = drawerDirection(androidx.compose.ui.platform.LocalLayoutDirection.current)
    com.helix.app.goal
        .GoalReminderNavigation(container.chatService, navController, departure)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = (currentEntry?.destination?.route ?: repository.initialDestination.route).substringBefore("?")
    val currentDestination = repository.destinations.firstOrNull { it.route == currentRoute }
    val currentSecondaryTitle = secondaryRouteTitle(currentRoute)
    val drawerIdentity =
        remember(container.chatService) {
            container.chatService.screen
                .map { it.openSessionId to it.sessionTitle }
                .distinctUntilChanged()
        }
    val drawerScreen by drawerIdentity.collectAsStateWithLifecycle(initialValue = null to "")
    val conversationDrawerState =
        ConversationDrawerState(
            currentSessionId = drawerScreen.first,
            currentTitle = drawerScreen.second,
        )

    fun leavePage(action: () -> Unit) {
        val source = navController.currentBackStackEntry
        departure.request({ navController.currentBackStackEntry === source }, navigate = action)
    }

    fun navigateToConversationRoot() {
        leavePage { navController.openSection(ShellDestination.Sessions.route) }
    }

    HelixTheme {
        metricsMessageId?.let { id ->
            com.helix.app.ui
                .MessageRunMetricsDialog(id, container.conversationDetails) { metricsMessageId = null }
        }
        citationUrl?.let(com.helix.core.model.citation.Citation::parse)?.let { citation ->
            com.helix.app.ui.CitationPreviewDialog(citation, resolve = {
                if (it.kind ==
                    "session"
                ) {
                    container.conversationDetails.resolveCitation(it)
                } else {
                    knowledge.resolveCitation(it)
                }
            }, onDismiss = { citationUrl = null })
        }
        com.helix.app.ui
            .UserQuestionDialog(container)
        com.helix.app.ui.AdaptiveHelixNavigation(
            drawerState = drawerState,
            drawerContent = {
                val context = androidx.compose.ui.platform.LocalContext.current
                GroupedNavigation(
                    destinations = repository.destinations,
                    header = {
                        com.helix.app.ui.AccountAccessEntry(
                            com.helix.app.ui.AccountEntryPresentation.DRAWER,
                            application.account,
                            onRefreshProviders = container.providerService::refresh,
                            onChanged = {},
                        )
                    },
                    currentRoute = currentRoute,
                    conversation = conversationDrawerState,
                    onCurrentConversation = {
                        navigateToConversationRoot()
                        scope.launch { drawerState.close() }
                    },
                    onNewConversation = {
                        leavePage {
                            container.chatService.newSessionDraft()
                            navController.openSection(ShellDestination.Sessions.route)
                        }
                        scope.launch { drawerState.close() }
                    },
                    onAllConversations = {
                        container.chatService.clearSessionSearch()
                        leavePage { navController.openSection(CONVERSATION_HISTORY_ROUTE) }
                        scope.launch { drawerState.close() }
                    },
                ) { route ->
                    if (route == ShellDestination.Terminal.route) {
                        com.helix.app.terminal.ManualTerminalModule
                            .open(context, ".")
                        scope.launch { drawerState.close() }
                    } else {
                        leavePage { navController.openSection(route) }
                        scope.launch { drawerState.close() }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    ShellTopBar(
                        currentDestination = currentDestination,
                        secondaryTitleRes = currentSecondaryTitle,
                        nested =
                            currentEntry?.savedStateHandle?.get<Boolean>(com.helix.app.ui.SECTION_ROOT_KEY) != true &&
                                navController.previousBackStackEntry != null,
                        onNavigation = { scope.launch { drawerState.open() } },
                        onBack = { backDispatcher?.onBackPressed() },
                    )
                },
            ) { padding ->
                androidx.compose.runtime.CompositionLocalProvider(
                    com.helix.app.ui.LocalNavigationDeparture provides departure,
                    com.helix.ui.shared.LocalCitationHandler provides { url -> citationUrl = url },
                    com.helix.app.ui.LocalRunMetricsHandler provides { id -> metricsMessageId = id },
                    com.helix.app.ui.LocalFileLocation provides { path ->
                        navController.navigate(
                            com.helix.app.ui
                                .fileLocationRoute(path),
                        )
                    },
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = repository.initialDestination.route,
                        modifier =
                            Modifier
                                .padding(padding)
                                .fillMaxSize()
                                .wrapContentWidth(androidx.compose.ui.Alignment.CenterHorizontally)
                                .widthIn(max = HelixUiDesign.contentWidth),
                        enterTransition = {
                            fadeIn(tween(180)) + slideInHorizontally(tween(180)) { direction * it / 12 }
                        },
                        exitTransition = {
                            fadeOut(
                                tween(140),
                            ) + slideOutHorizontally(tween(180)) { -direction * it / 12 }
                        },
                        popEnterTransition = {
                            fadeIn(
                                tween(180),
                            ) + slideInHorizontally(tween(180)) { -direction * it / 12 }
                        },
                        popExitTransition = {
                            fadeOut(
                                tween(140),
                            ) + slideOutHorizontally(tween(180)) { direction * it / 12 }
                        },
                    ) {
                        repository.destinations.forEach { destination ->
                            composable(
                                destination.navigationRoute(),
                                arguments = destination.navigationArguments(),
                            ) { entry ->
                                androidx.compose.runtime.CompositionLocalProvider(
                                    com.helix.app.ui.LocalPageReturn provides { navController.returnFrom(entry) },
                                ) {
                                    DestinationScreen(
                                        destination,
                                        container,
                                        navController,
                                        entry,
                                        onMobileUseSession = { target ->
                                            navController.completionFor(entry).complete {
                                                mobileUseDialog.open(target, entry.id)
                                            }
                                        },
                                        onOpenDrawer = { scope.launch { drawerState.open() } },
                                        initialModelSource =
                                            ProviderProvisioningKind.entries.firstOrNull {
                                                it.name ==
                                                    entry.arguments?.getString("source")
                                            },
                                    )
                                }
                            }
                        }
                        composable(
                            com.helix.app.ui.FILE_LOCATION_ROUTE,
                            arguments = listOf(navArgument("reference") { type = NavType.StringType }),
                        ) { entry ->
                            val path =
                                com.helix.core.workspace.FileScopePath.fromModelReference(
                                    requireNotNull(entry.arguments?.getString("reference")),
                                )
                            FilesScreen(
                                container.fileManager,
                                container.safTree,
                                container.featureFiles,
                                initialDirectory = path.parent,
                                initialFile = path,
                                handoff =
                                    com.helix.app.ui.FileConversationHandoff(container.chatService) {
                                        navigateToConversationRoot()
                                    },
                            )
                        }
                        composable(com.helix.app.projects.ui.PROJECT_ROUTE) { entry ->
                            com.helix.app.projects.ui.ProjectDetailScreen(
                                container,
                                requireNotNull(entry.arguments?.getString("projectId")),
                                onOpenSession = { id ->
                                    container.chatService.openSession(id)
                                    navigateToConversationRoot()
                                },
                                onOpenDraft = { navigateToConversationRoot() },
                                onDeleted = { navController.returnFrom(entry) },
                                onOpenTask = {
                                    navController.navigateFrom(
                                        entry,
                                        com.helix.app.ui
                                            .tasksTurnRoute(it),
                                    )
                                },
                                onOpenCommand = {
                                    turnId,
                                    callId,
                                    ->
                                    navController.navigateFrom(entry, commandDetailRoute(turnId, callId))
                                },
                            )
                        }
                        composable(CONVERSATION_HISTORY_ROUTE) {
                            ConversationHistoryScreen(
                                directory = container.chatService.directory,
                                actions =
                                    SessionHistoryActions(
                                        container.chatService::archiveSession,
                                        container.chatService::restoreSession,
                                        container.chatService::renameSession,
                                    ),
                                focusSearch = false,
                                onOpenConversation = { sessionId ->
                                    container.chatService.openSession(sessionId)
                                    navigateToConversationRoot()
                                },
                                onNewConversation = {
                                    container.chatService.newSessionDraft()
                                    navigateToConversationRoot()
                                },
                            )
                        }
                        composable(CONVERSATION_SEARCH_ROUTE) {
                            ConversationHistoryScreen(
                                directory = container.chatService.directory,
                                actions =
                                    SessionHistoryActions(
                                        container.chatService::archiveSession,
                                        container.chatService::restoreSession,
                                        container.chatService::renameSession,
                                    ),
                                focusSearch = true,
                                onOpenConversation = { sessionId ->
                                    container.chatService.openSession(sessionId)
                                    navigateToConversationRoot()
                                },
                                onNewConversation = {
                                    container.chatService.newSessionDraft()
                                    navigateToConversationRoot()
                                },
                            )
                        }
                        composable(EXPERTS_ROUTE) { entry ->
                            DestinationScreen(
                                ShellDestination.Extensions,
                                container,
                                navController,
                                entry,
                                onMobileUseSession = { target -> mobileUseDialog.open(target, entry.id) },
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                initialCapabilitiesTab = 1,
                            )
                        }
                        composable(CONVERSATION_SETTINGS_ROUTE) { entry ->
                            SessionSettingsScreen(
                                accountEntry = { changed ->
                                    com.helix.app.ui.AccountAccessEntry(
                                        com.helix.app.ui.AccountEntryPresentation.MODEL_PICKER,
                                        application.account,
                                        onRefreshProviders = container.providerService::refresh,
                                        onChanged = changed,
                                    )
                                },
                                chatService = container.chatService,
                                dashboard = container.taskDashboard,
                                skills = container.skillRepository,
                                providerService = container.providerService,
                                permissionEdit = container.sessionPermissionEdit,
                                files = container.fileManager,
                                projects = container.projects,
                                onProject = {
                                    navController.navigateFrom(
                                        entry,
                                        com.helix.app.projects.ui
                                            .projectRoute(it),
                                    )
                                },
                                onConfigureModelSource = { source ->
                                    navController.navigateFrom(
                                        entry,
                                        "${ShellDestination.Models.route}?source=${source.name}",
                                    )
                                },
                                onExtensions = { navController.navigateFrom(entry, ShellDestination.Extensions.route) },
                            )
                        }
                        composable(SETUP_READINESS_ROUTE) { entry ->
                            CapabilityReadinessScreen(
                                container,
                                onOpenModels = { navController.navigateFrom(entry, ShellDestination.Models.route) },
                                onOpenRuntime = { navController.navigateFrom(entry, SETUP_RUNTIME_ROUTE) },
                            )
                        }
                        composable(SETUP_CAPABILITIES_ROUTE) { entry ->
                            CapabilitiesScreenDestination(
                                container,
                                onOpenSystemPermissions = {
                                    navController.navigateFrom(
                                        entry,
                                        SETTINGS_SYSTEM_PERMISSIONS_ROUTE,
                                    )
                                },
                                onOpenSafety = { navController.navigateFrom(entry, SETTINGS_PERMISSIONS_ROUTE) },
                                onOpenRuntime = { navController.navigateFrom(entry, SETUP_RUNTIME_ROUTE) },
                                onOpenExtensions = {
                                    navController.navigateFrom(
                                        entry,
                                        ShellDestination.Extensions.route,
                                    )
                                },
                            )
                        }
                        composable(SETUP_RUNTIME_ROUTE) {
                            RuntimeSetupScreen(container.profileStore)
                        }
                        composable(SETTINGS_DEFAULTS_ROUTE) {
                            AppAgentDefaultsScreen(container.runControlStore, container.chatService)
                        }
                        composable(SETTINGS_PERMISSIONS_ROUTE) { entry ->
                            PermissionsSafetyScreen(
                                profileStore = container.profileStore,
                                egressRules = container.egressRules,
                                lanScopeStore = container.lanScopeStore,
                                sessionPermissionEdit = container.sessionPermissionEdit,
                                toolPipeline = container.toolPipeline,
                                onSystemPermissions = {
                                    navController.navigateFrom(
                                        entry,
                                        SETTINGS_SYSTEM_PERMISSIONS_ROUTE,
                                    )
                                },
                            )
                        }
                        composable(SETTINGS_SYSTEM_PERMISSIONS_ROUTE) { entry ->
                            PermissionsScreenDestination(
                                container,
                            ) { navController.navigateFrom(entry, ShellDestination.Files.route) }
                        }
                        mobileUseSettingsDestination(navController)
                        pluginConfigurationDestination(navController, container.pluginService)
                        composable(SETTINGS_STORAGE_ROUTE) {
                            StorageUsageScreen(
                                load = { checkNotNull(container.storageUsage).snapshot() },
                                cleanProviderEvidence =
                                    if (com.helix.app.profile.ChannelCapabilities.ADVANCED_AVAILABLE) {
                                        { after ->
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                container.privacyDeletionService.cleanReplayEvidence(after)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                            )
                        }
                        composable(SETTINGS_AUDIT_ROUTE) { entry ->
                            AuditScreenDestination(container, navController, entry)
                        }
                        // HXA-194: the command details page — its OWN route, not one of the drawer's
                        // destinations: the back button (and the system back) return to exactly
                        // the page the detail was opened from (the task page or the chat tool row).
                        composable(
                            COMMAND_DETAIL_ROUTE,
                            arguments =
                                listOf(
                                    navArgument("turnId") { type = NavType.StringType },
                                    navArgument("callId") { type = NavType.StringType },
                                ),
                        ) { entry ->
                            CommandResultDetailScreen(
                                container.conversationDetails,
                                requireNotNull(entry.arguments?.getString("turnId")),
                                requireNotNull(entry.arguments?.getString("callId")),
                                onBack = { navController.returnFrom(entry) },
                                onOpenSession = { sessionId ->
                                    container.chatService.openSession(sessionId)
                                    navController.openSection(ShellDestination.Sessions.route)
                                },
                            )
                        }
                        // HXA-203: "return to the producing task" — the task dashboard's own route
                        // (same pattern as command details): it lands on the turn's result dialog
                        // and system back returns to the page the artifact row was opened from.
                        composable(
                            TASKS_TURN_ROUTE,
                            arguments =
                                listOf(
                                    navArgument("turnId") { type = NavType.StringType },
                                ),
                        ) { entry ->
                            TasksScreen(
                                container.chatService,
                                container.taskDashboard,
                                container.conversationDetails,
                                container.fileManager,
                                onOpenSession = { sessionId ->
                                    container.chatService.openSession(sessionId)
                                    navController.openSection(ShellDestination.Sessions.route)
                                },
                                onOpenCommandDetail = { turnId, callId ->
                                    navController.navigate(commandDetailRoute(turnId, callId))
                                },
                                initialTurnId = requireNotNull(entry.arguments?.getString("turnId")),
                                onBack = { navController.returnFrom(entry) },
                            )
                        }
                    }
                }
            }
        }
        com.helix.app.ui.MobileUseDialogHost(
            mobileUseDialog,
            navController,
            container.pluginService,
            container.chatService,
        )
    }
    // Register when the overlay opens: NavHost page handlers may be composed later than
    // the shell. A permanently registered handler cannot claim priority just by enabling it.
    if (drawerState.currentValue != DrawerValue.Closed || drawerState.targetValue != DrawerValue.Closed) {
        BackHandler { scope.launch { drawerState.close() } }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
// Exhaustive shell destination dispatch.
private fun DestinationScreen(
    destination: ShellDestination,
    container: AppContainer,
    navController: NavController,
    sourceEntry: androidx.navigation.NavBackStackEntry,
    onMobileUseSession: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    initialModelSource: ProviderProvisioningKind? = null,
    initialCapabilitiesTab: Int = 0,
) {
    when (destination) {
        ShellDestination.Reminders -> {
            com.helix.app.reminder.ReminderScreen(
                (androidx.compose.ui.platform.LocalContext.current.applicationContext as HelixApplication).reminders,
                providers = container.providerService,
                scheduled = container.scheduledAgents,
                onOpenSession = { sessionId ->
                    container.chatService.openSession(sessionId)
                    navController.openSectionFrom(sourceEntry, ShellDestination.Sessions.route)
                },
            )
        }

        ShellDestination.Knowledge -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            com.helix.app.knowledge.KnowledgeScreen(
                (context.applicationContext as HelixApplication).knowledge,
                onReference = { references ->
                    val completion = navController.completionFor(sourceEntry)
                    val target = container.chatService.screen.value.openSessionId
                    try {
                        val applied = container.chatService.stageKnowledgeReferences(target, references).await()
                        if (applied) completion.complete { navController.openSection(ShellDestination.Sessions.route) }
                        applied
                    } finally {
                        completion.invalidate()
                    }
                },
            )
        }

        ShellDestination.Sessions -> {
            val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as HelixApplication
            val accountIdentity by application.account.identityState.collectAsStateWithLifecycle()
            val accountStatus by application.account.status.collectAsStateWithLifecycle()
            AppConversationRoute(
                container.chatService,
                container.toolVisionConsent,
                container.taskDashboard,
                container.conversationDetails,
                container.conversationExperts,
                container.providerService,
                container.privacyDeletionService,
                container.fileManager,
                accountEntry = { changed ->
                    com.helix.app.ui.AccountAccessEntry(
                        com.helix.app.ui.AccountEntryPresentation.MODEL_PICKER,
                        application.account,
                        onRefreshProviders = container.providerService::refresh,
                        onChanged = changed,
                    )
                },
                voiceInput = { dictation, actions ->
                    com.helix.app.ui.OfflineAsrGate(
                        application.account,
                        application.speechSettings,
                        application.offlineAsr,
                        actions.onDismiss,
                        actions.onError,
                    ) {
                        com.helix.app.ui.InlineVoiceInput(
                            onDismiss = actions.onDismiss,
                            onAccept = actions.onAccept,
                            onPreview = actions.onPreview,
                            onError = actions.onError,
                            dictation = dictation,
                            account = application.account,
                            speechSettings = application.speechSettings,
                            consent = application.speechConsent,
                            onStopSpeech = application::stopSpeechForInput,
                            captureFactory = { context, scope ->
                                com.helix.app.voice.VoiceCapture(
                                    context,
                                    scope,
                                    application.speechSettings.asrFactory(application.account) {
                                        com.helix.app.voice
                                            .OfflineAsrFactory(context)
                                    },
                                )
                            },
                        )
                    }
                },
                knowledge = application.knowledge.references,
                reader = application.readAloud,
                speechConsent = application.speechConsent,
                replyAudioSettings = application.replyAudioSettings,
                accountIdentity = accountIdentity,
                accountStatus = accountStatus,
                sessionPermissionEdit = container.sessionPermissionEdit,
                skills = container.skillRepository,
                sessionExport = container.sessionExport,
                sessionAccess = container.sessionAccessService,
                sessionCreation = container.sessionCreationService,
                connectors = container.pluginService,
                onManageExperts = { navController.navigateFrom(sourceEntry, EXPERTS_ROUTE) },
                onExtensions = { navController.navigateFrom(sourceEntry, ShellDestination.Extensions.route) },
                onPluginConfigure = { id, reason -> navController.configurePluginFrom(sourceEntry, id, reason) },
                onMobileUseSession = onMobileUseSession,
                onNavigation = onOpenDrawer,
                onModels = { navController.navigateFrom(sourceEntry, ShellDestination.Models.route) },
                onConfigureModelSource = { source ->
                    navController.navigateFrom(sourceEntry, "${ShellDestination.Models.route}?source=${source.name}")
                },
                onAgentDefaults = { navController.navigateFrom(sourceEntry, SETTINGS_DEFAULTS_ROUTE) },
                onPermissions = { navController.navigateFrom(sourceEntry, SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
                onSessionSettings = { navController.navigateFrom(sourceEntry, CONVERSATION_SETTINGS_ROUTE) },
                onGit = { navController.navigateFrom(sourceEntry, ShellDestination.Git.route) },
                onOpenCommandDetail = { turnId, callId ->
                    navController.navigateFrom(sourceEntry, commandDetailRoute(turnId, callId))
                },
            )
        }

        // P0-B: the cross-session task dashboard (doc section 13).
        // Opening a row binds that session and returns to chat.
        ShellDestination.Tasks -> {
            TasksScreen(
                container.chatService,
                container.taskDashboard,
                container.conversationDetails,
                container.fileManager,
                onOpenSession = { sessionId ->
                    container.chatService.openSession(sessionId)
                    navController.openSectionFrom(sourceEntry, ShellDestination.Sessions.route)
                },
                // HXA-194: the task page's command entry opens the details page; the system
                // back from there returns to the tasks dashboard.
                onOpenCommandDetail = { turnId, callId ->
                    navController.navigateFrom(sourceEntry, commandDetailRoute(turnId, callId))
                },
            )
        }

        // P0-B: the Artifact Center (doc section 28) — the deliverable results of finished
        // tasks, each shareable and openable, so outcomes are reachable outside the chat.
        ShellDestination.Artifacts -> {
            ArtifactsScreenDestination(
                container,
                onOpenSession = { sessionId ->
                    container.chatService.openSession(sessionId)
                    navController.openSectionFrom(sourceEntry, ShellDestination.Sessions.route)
                },
                // HXA-203: an artifact's "view task" action returns to the turn that wrote
                // it, through the dedicated tasks-turn route (system back pops back here).
                onOpenTask = { turnId ->
                    navController.navigateFrom(sourceEntry, tasksTurnRoute(turnId))
                },
            )
        }

        // P0-B: the Git status / diff / changed-files surface (doc section 29) — the workspace
        // repository's staged / unstaged / untracked changes, read on-device with JGit.
        ShellDestination.Git -> {
            GitStatusScreenDestination(container.chatService, container.fileManager)
        }

        ShellDestination.Settings -> {
            val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val backup =
                remember(container, context) {
                    com.helix.app.backup.AppBackupService(
                        context,
                        container.storage,
                        container.fileManager,
                        (context as HelixApplication).knowledge,
                        requireNotNull(container.executionOwnership),
                    )
                }
            val application = context as HelixApplication
            SettingsScreen(
                accountEntry = {
                    com.helix.app.ui.AccountAccessEntry(
                        com.helix.app.ui.AccountEntryPresentation.SETTINGS,
                        application.account,
                        onRefreshProviders = container.providerService::refresh,
                        onChanged = {},
                    )
                },
                speechSettings = {
                    com.helix.app.ui.CloudSpeechSettingsEntry(
                        application.speechSettings,
                        application.account,
                        application.offlineAsr,
                        application.readAloud,
                    )
                },
                memory = container.memory,
                contentBackup = backup,
            )
        }

        ShellDestination.Models -> {
            ModelsConnectionsScreen(container.providerService, initialModelSource)
        }

        ShellDestination.Extensions -> {
            com.helix.app.ui.CapabilitiesScreen(
                container.conversationExperts,
                container.pluginService,
                initialCapabilitiesTab,
            ) {
                ExtensionsScreen(
                    container.skillAuthoringService,
                    container.skillInstallationService,
                    container.pluginService,
                    container.marketplaceService,
                    skillRepository = container.skillRepository,
                    onSkillInvoke = { target, activation ->
                        val completion = navController.completionFor(sourceEntry)
                        try {
                            val applied =
                                container.chatService
                                    .stageSkillActivation(
                                        target,
                                        com.helix.core.model.input.InputSkillActivation(
                                            activation.key.source.name,
                                            activation.key.name,
                                            activation.key.snapshotHash,
                                            activation.arguments,
                                            activation.key.ownerId,
                                        ),
                                    ).await()
                            if (applied && container.chatService.screen.value.openSessionId == target) {
                                completion.complete {
                                    navController.openSection(
                                        ShellDestination.Sessions.route,
                                    )
                                }
                            }
                            applied
                        } finally {
                            completion.invalidate()
                        }
                    },
                    onSessionSettings = { navController.navigateFrom(sourceEntry, CONVERSATION_SETTINGS_ROUTE) },
                    prepareSession = container.chatService::materializeDraftSession,
                    currentSession = { container.chatService.screen.value.openSessionId },
                    onPluginConfigure = { id, reason -> navController.configurePluginFrom(sourceEntry, id, reason) },
                    onMobileUseSession = onMobileUseSession,
                    onMobileUseSettings = {
                        navController.navigateFrom(sourceEntry, com.helix.app.ui.MOBILE_USE_SETTINGS_ROUTE) {
                            launchSingleTop =
                                true
                        }
                    },
                    onPermissions = { navController.navigateFrom(sourceEntry, SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
                    importFiles = container.fileManager,
                    filePicker = { selected, cancel ->
                        com.helix.app.ui.FileSelectionDialog(
                            container.fileManager,
                            container.safTree,
                            container.featureFiles,
                            selected,
                            cancel,
                        )
                    },
                )
            }
        }

        ShellDestination.Projects -> {
            container.projects?.let { service ->
                com.helix.app.projects.ui.ProjectsScreen(service, container.fileManager) { id ->
                    navController.navigateFrom(
                        sourceEntry,
                        com.helix.app.projects.ui
                            .projectRoute(id),
                    )
                }
            }
        }

        ShellDestination.Setup -> {
            CapabilityReadinessScreen(
                container,
                onOpenModels = { navController.navigateFrom(sourceEntry, ShellDestination.Models.route) },
                onOpenRuntime = { navController.navigateFrom(sourceEntry, SETUP_RUNTIME_ROUTE) },
            )
        }

        // HXA-046: the file-management screen over the always-available
        // sources (Workspace, always; developer all-files roots, read-only)
        // + HXA-058: the import/export entries over the HXA-044 pipelines.
        ShellDestination.Files -> {
            FilesScreen(
                container.fileManager,
                container.safTree,
                container.featureFiles,
                container.manualTerminal != null,
                handoff =
                    com.helix.app.ui.FileConversationHandoff(container.chatService) {
                        navController.openSectionFrom(sourceEntry, ShellDestination.Sessions.route)
                    },
            )
        }

        // HXA-060: the minimal hardened WebView browser.
        ShellDestination.Browser -> {
            BrowserScreen(
                container.browser,
                onExitBrowser = {
                    if (navController.currentBackStackEntry === sourceEntry) {
                        navController.openSectionFrom(sourceEntry, ShellDestination.Sessions.route)
                    }
                },
            ) { sessionId ->
                if (navController.currentBackStackEntry === sourceEntry) {
                    container.chatService.openSession(sessionId)
                    navController.openSectionFrom(sourceEntry, ShellDestination.Sessions.route)
                }
            }
        }

        ShellDestination.Terminal -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.helix.app.terminal.ManualTerminalModule
                    .open(context, ".")
                if (navController.currentBackStackEntry === sourceEntry) navController.popBackStack()
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun PermissionsScreenDestination(
    container: AppContainer,
    onFileLocations: () -> Unit,
) {
    val profile by container.profileStore.flow.collectAsStateWithLifecycle()
    com.helix.app.ui.SystemPermissionsScreen(
        onFileLocations = onFileLocations,
        devicePermissions =
            if (com.helix.app.deviceaccess.DeviceAccessModule.AVAILABLE) {
                {
                    com.helix.app.deviceaccess.DeviceAccessModule
                        .Permissions()
                }
            } else {
                null
            },
        advancedPermissions =
            if (com.helix.app.profile.ChannelCapabilities.ADVANCED_AVAILABLE &&
                profile == com.helix.core.model.SafetyProfile.ADVANCED
            ) {
                {
                    com.helix.app.root.RootModule
                        .Section(profile)
                }
            } else {
                null
            },
        filePermissions =
            if (AllFilesModule.AVAILABLE) {
                { AllFilesModule.render(container.profileStore) }
            } else {
                null
            },
    )
}

/**
 * Observes the chat session list as Compose state so the audit page's 会话 filter stays in
 * sync (reading `StateFlow.value` directly in composition would never recompose on change).
 */
@Composable
@Suppress("FunctionName")
private fun AuditScreenDestination(
    container: AppContainer,
    navController: NavController,
    sourceEntry: androidx.navigation.NavBackStackEntry,
) {
    val sessions by container.chatService.sessions.collectAsStateWithLifecycle()
    AuditScreen(
        container.auditLogService,
        sessions,
        container.diagnosticReport,
        onReadiness = { navController.navigateFrom(sourceEntry, SETUP_READINESS_ROUTE) },
        onCapabilities = { navController.navigateFrom(sourceEntry, SETUP_CAPABILITIES_ROUTE) },
        onOpenCommand = { turnId, callId ->
            navController.navigateFrom(sourceEntry, commandDetailRoute(turnId, callId))
        },
    )
}

/** Sessions own their header; all other routes share the same compact visual style. */
@Composable
@Suppress("FunctionName")
private fun ShellTopBar(
    currentDestination: ShellDestination?,
    secondaryTitleRes: Int?,
    nested: Boolean,
    onNavigation: () -> Unit,
    onBack: () -> Unit,
) {
    if (currentDestination != ShellDestination.Sessions || secondaryTitleRes != null) {
        // Scaffold delegates top insets to its topBar; our compact Row is not a Material TopAppBar.
        Box(
            Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
        ) {
            if (secondaryTitleRes != null) {
                CompactPageHeader(stringResource(secondaryTitleRes), onBack, back = true)
            } else if (currentDestination != null) {
                CompactPageHeader(
                    stringResource(currentDestination.titleRes),
                    if (nested) onBack else onNavigation,
                    back = nested,
                )
            }
        }
    }
}

private fun ShellDestination.navigationRoute(): String =
    if (this == ShellDestination.Models) "$route?source={source}" else route

private fun ShellDestination.navigationArguments(): List<androidx.navigation.NamedNavArgument> =
    if (this == ShellDestination.Models) {
        listOf(
            navArgument("source") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        )
    } else {
        emptyList()
    }
