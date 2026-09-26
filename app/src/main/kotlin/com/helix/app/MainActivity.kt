package com.helix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.helix.app.ui.ArtifactsScreenDestination
import com.helix.app.ui.AuditScreen
import com.helix.app.ui.COMMAND_DETAIL_ROUTE
import com.helix.app.ui.CONVERSATION_HISTORY_ROUTE
import com.helix.app.ui.CONVERSATION_SEARCH_ROUTE
import com.helix.app.ui.CapabilitiesScreenDestination
import com.helix.app.ui.CapabilityReadinessScreen
import com.helix.app.ui.ChatScreen
import com.helix.app.ui.CommandResultDetailScreen
import com.helix.app.ui.CompactPageHeader
import com.helix.app.ui.ConversationDrawerState
import com.helix.app.ui.ConversationHistoryScreen
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
import com.helix.app.ui.SETTINGS_SYSTEM_PERMISSIONS_ROUTE
import com.helix.app.ui.SETUP_CAPABILITIES_ROUTE
import com.helix.app.ui.SETUP_READINESS_ROUTE
import com.helix.app.ui.SETUP_RUNTIME_ROUTE
import com.helix.app.ui.SettingsScreen
import com.helix.app.ui.SetupScreen
import com.helix.app.ui.TASKS_TURN_ROUTE
import com.helix.app.ui.TasksScreen
import com.helix.app.ui.commandDetailRoute
import com.helix.app.ui.secondaryRouteTitle
import com.helix.app.ui.tasksTurnRoute
import com.helix.feature.browser.BrowserViewOwner
import com.helix.feature.browser.ui.BrowserScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var consumedReminderId: String? = null

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

    private lateinit var browserOwner: BrowserViewOwner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumedReminderId = savedInstanceState?.getString("consumed_goal_reminder")
        val container = (application as HelixApplication).appContainer
        browserOwner = BrowserViewOwner(this)
        container.browser.attach(browserOwner)
        // HXA-056 / PX-06: a share intent (text, images, or PDF/DOCX/HTML files) becomes a
        // local DRAFT — imported + pre-filled, never auto-sent (ADR-0014 §5). Re-runs when the
        // user shares again into the running task (onNewIntent).
        val draft = ShareIntentDraft.draftFrom(intent)
        val hasGoalReminder =
            com.helix.app.goal
                .goalReminderId(intent) != null
        container.chatService.acceptShareDraft(draft.text, draft.imageUris, draft.fileUris)
        acceptGoalReminder(intent)
        if (draft.isEmpty && !hasGoalReminder) container.chatService.restoreConversationLaunchTarget()
        setContent { HelixApp(container) }
    }

    // Best-effort WebView pause on background; onPause does not pause JavaScript globally.
    override fun onPause() {
        super.onPause()
        (application as HelixApplication).appContainer.browser.pause(browserOwner)
    }

    override fun onStop() {
        // A Root manager may revoke policy without killing an existing libsu shell. Never retain
        // developer Root authority after the app leaves the foreground; REQUESTING is preserved
        // so the manager's grant dialog can complete normally.
        RootModule.onAppBackgrounded()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        (application as HelixApplication).appContainer.browser.resume(browserOwner)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumedReminderId = null
        acceptGoalReminder(intent)
        val draft = ShareIntentDraft.draftFrom(intent)
        (application as HelixApplication).appContainer.chatService.acceptShareDraft(
            draft.text,
            draft.imageUris,
            draft.fileUris,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("consumed_goal_reminder", consumedReminderId)
        super.onSaveInstanceState(outState)
    }

    private fun acceptGoalReminder(intent: Intent) {
        val goalId =
            com.helix.app.goal
                .goalReminderId(intent) ?: return
        // Persist consumption without changing the Activity launch identity.
        if (goalId != consumedReminderId) {
            consumedReminderId = goalId
            (application as HelixApplication).appContainer.chatService.openGoalReminder(goalId)
        }
    }

    /**
     * The tab STATE (app-scoped [BrowserController]) survives activity recreation, but its
     * WebViews are UI resources: destroy them with the activity. A tab whose host was just
     * destroyed renders its placeholder until the next navigation rebuilds the host lazily.
     */
    override fun onDestroy() {
        super.onDestroy()
        (application as HelixApplication).appContainer.browser.detach(browserOwner)
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
@OptIn(ExperimentalMaterial3Api::class)
// The Compose UI DSL keeps this screen intentionally in one composable; detekt's LongMethod
// threshold does not model UI composition well, so it is suppressed here only.
@Suppress("FunctionName", "LongMethod")
@Composable
internal fun HelixApp(container: AppContainer) {
    var noticeDismissed by remember { mutableStateOf(container.firstLaunch.noticeSeen) }
    if (!noticeDismissed) {
        HelixTheme {
            FirstLaunchNoticeScreen(
                onContinue = {
                    container.firstLaunch.markSeen()
                    noticeDismissed = true
                },
            )
        }
        return
    }

    val repository = container.shellRepository
    val navController = rememberNavController()
    com.helix.app.goal
        .GoalReminderNavigation(container.chatService, navController)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route ?: repository.initialDestination.route
    val currentDestination = repository.destinations.firstOrNull { it.route == currentRoute }
    val currentSecondaryTitle = secondaryRouteTitle(currentRoute)
    val drawerSessions by container.chatService.sessions.collectAsState()
    val drawerScreen by container.chatService.screen.collectAsState()
    val conversationDrawerState =
        ConversationDrawerState(
            currentSessionId = drawerScreen.openSessionId,
            currentTitle = drawerScreen.sessionTitle,
            recent = drawerSessions.filterNot { it.isArchived }.take(6),
        )

    fun navigateToConversationRoot() {
        navController.navigate(ShellDestination.Sessions.route) {
            launchSingleTop = true
            popUpTo(repository.initialDestination.route)
        }
    }

    HelixTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    GroupedNavigation(
                        destinations = repository.destinations,
                        currentRoute = currentRoute,
                        conversation = conversationDrawerState,
                        onCurrentConversation = {
                            navigateToConversationRoot()
                            scope.launch { drawerState.close() }
                        },
                        onNewConversation = {
                            container.chatService.newSessionDraft()
                            navigateToConversationRoot()
                            scope.launch { drawerState.close() }
                        },
                        onSearchConversations = {
                            container.chatService.clearSessionSearch()
                            navController.navigate(CONVERSATION_SEARCH_ROUTE) { launchSingleTop = true }
                            scope.launch { drawerState.close() }
                        },
                        onAllConversations = {
                            container.chatService.clearSessionSearch()
                            navController.navigate(CONVERSATION_HISTORY_ROUTE) { launchSingleTop = true }
                            scope.launch { drawerState.close() }
                        },
                        onOpenConversation = { sessionId ->
                            container.chatService.openSession(sessionId)
                            navigateToConversationRoot()
                            scope.launch { drawerState.close() }
                        },
                    ) { destination ->
                        if (destination == ShellDestination.Terminal) {
                            com.helix.app.terminal.ManualTerminalModule
                                .open(context, ".")
                            scope.launch { drawerState.close() }
                        } else {
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                                popUpTo(repository.initialDestination.route)
                            }
                            scope.launch { drawerState.close() }
                        }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    ShellTopBar(
                        currentDestination = currentDestination,
                        secondaryTitleRes = currentSecondaryTitle,
                        onNavigation = { scope.launch { drawerState.open() } },
                        onBack = { navController.popBackStack() },
                    )
                },
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = repository.initialDestination.route,
                    modifier = Modifier.padding(padding),
                ) {
                    repository.destinations.forEach { destination ->
                        composable(destination.route) {
                            DestinationScreen(
                                destination,
                                container,
                                navController,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                            )
                        }
                    }
                    composable(CONVERSATION_HISTORY_ROUTE) {
                        ConversationHistoryScreen(
                            chatService = container.chatService,
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
                            chatService = container.chatService,
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
                    composable(SETUP_READINESS_ROUTE) {
                        CapabilityReadinessScreen(
                            container,
                            onOpenModels = { navController.navigate(ShellDestination.Models.route) },
                            onOpenRuntime = { navController.navigate(SETUP_RUNTIME_ROUTE) },
                        )
                    }
                    composable(SETUP_CAPABILITIES_ROUTE) {
                        CapabilitiesScreenDestination(
                            container,
                            onOpenSystemPermissions = { navController.navigate(SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
                            onOpenSafety = { navController.navigate(SETTINGS_PERMISSIONS_ROUTE) },
                            onOpenRuntime = { navController.navigate(SETUP_RUNTIME_ROUTE) },
                            onOpenExtensions = { navController.navigate(ShellDestination.Extensions.route) },
                        )
                    }
                    composable(SETUP_RUNTIME_ROUTE) {
                        RuntimeSetupScreen(container.profileStore)
                    }
                    composable(SETTINGS_DEFAULTS_ROUTE) {
                        AppAgentDefaultsScreen(container.runControlStore, container.chatService)
                    }
                    composable(SETTINGS_PERMISSIONS_ROUTE) {
                        PermissionsSafetyScreen(
                            profileStore = container.profileStore,
                            egressRules = container.storage.highSensitivityRules,
                            lanScopeStore = container.lanScopeStore,
                            chatService = container.chatService,
                            sessionPermissionEdit = container.sessionPermissionEdit,
                            toolPipeline = container.toolPipeline,
                            onSystemPermissions = { navController.navigate(SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
                        )
                    }
                    composable(SETTINGS_SYSTEM_PERMISSIONS_ROUTE) {
                        PermissionsScreenDestination(container)
                    }
                    composable(SETTINGS_AUDIT_ROUTE) {
                        AuditScreenDestination(container)
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
                            container.chatService,
                            requireNotNull(entry.arguments?.getString("turnId")),
                            requireNotNull(entry.arguments?.getString("callId")),
                            onBack = { navController.popBackStack() },
                            onOpenSession = { sessionId ->
                                container.chatService.openSession(sessionId)
                                navController.navigate(ShellDestination.Sessions.route) {
                                    launchSingleTop = true
                                }
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
                            container.fileManager,
                            onOpenSession = { sessionId ->
                                container.chatService.openSession(sessionId)
                                navController.navigate(ShellDestination.Sessions.route) {
                                    launchSingleTop = true
                                }
                            },
                            onOpenCommandDetail = { turnId, callId ->
                                navController.navigate(commandDetailRoute(turnId, callId))
                            },
                            initialTurnId = requireNotNull(entry.arguments?.getString("turnId")),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun DestinationScreen(
    destination: ShellDestination,
    container: AppContainer,
    navController: NavController,
    onOpenDrawer: () -> Unit,
) {
    when (destination) {
        ShellDestination.Sessions -> {
            ChatScreen(
                container.chatService,
                container.providerService,
                container.privacyDeletionService,
                container.fileManager,
                sessionExport = container.sessionExport,
                connectors = container.connectorService,
                onExtensions = { navController.navigate(ShellDestination.Extensions.route) },
                onNavigation = onOpenDrawer,
                onModels = { navController.navigate(ShellDestination.Models.route) },
                onAgentDefaults = { navController.navigate(SETTINGS_DEFAULTS_ROUTE) },
                onPermissions = { navController.navigate(SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
                onOpenCommandDetail = { turnId, callId ->
                    navController.navigate(commandDetailRoute(turnId, callId))
                },
            )
        }

        // P0-B: the cross-session task dashboard (doc section 13).
        // Opening a row binds that session and returns to chat.
        ShellDestination.Tasks -> {
            TasksScreen(
                container.chatService,
                container.fileManager,
                onOpenSession = { sessionId ->
                    container.chatService.openSession(sessionId)
                    navController.navigate(ShellDestination.Sessions.route) {
                        launchSingleTop = true
                    }
                },
                // HXA-194: the task page's command entry opens the details page; the system
                // back from there returns to the tasks dashboard.
                onOpenCommandDetail = { turnId, callId ->
                    navController.navigate(commandDetailRoute(turnId, callId))
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
                    navController.navigate(ShellDestination.Sessions.route) {
                        launchSingleTop = true
                    }
                },
                // HXA-203: an artifact's "view task" action returns to the turn that wrote
                // it, through the dedicated tasks-turn route (system back pops back here).
                onOpenTask = { turnId ->
                    navController.navigate(tasksTurnRoute(turnId))
                },
            )
        }

        // P0-B: the Git status / diff / changed-files surface (doc section 29) — the workspace
        // repository's staged / unstaged / untracked changes, read on-device with JGit.
        ShellDestination.Git -> {
            GitStatusScreenDestination()
        }

        ShellDestination.Settings -> {
            SettingsScreen(
                onDefaults = { navController.navigate(SETTINGS_DEFAULTS_ROUTE) },
                onPermissions = { navController.navigate(SETTINGS_PERMISSIONS_ROUTE) },
                onAudit = { navController.navigate(SETTINGS_AUDIT_ROUTE) },
            )
        }

        ShellDestination.Models -> {
            ModelsConnectionsScreen(container.providerService)
        }

        ShellDestination.Extensions -> {
            ExtensionsScreen(
                container.skillAuthoringService,
                container.skillInstallationService,
                container.connectorService,
                container.marketplaceService,
            )
        }

        ShellDestination.Setup -> {
            SetupScreen(
                onReadiness = { navController.navigate(SETUP_READINESS_ROUTE) },
                onCapabilities = { navController.navigate(SETUP_CAPABILITIES_ROUTE) },
                onRuntime = { navController.navigate(SETUP_RUNTIME_ROUTE) },
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
            )
        }

        // HXA-060: the minimal hardened WebView browser.
        ShellDestination.Browser -> {
            BrowserScreen(container.browser)
        }

        ShellDestination.Terminal -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.helix.app.terminal.ManualTerminalModule
                    .open(context, ".")
                navController.popBackStack()
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun PermissionsScreenDestination(container: AppContainer) {
    com.helix.app.ui.SystemPermissionsScreen(
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
private fun AuditScreenDestination(container: AppContainer) {
    val sessions by container.chatService.sessions.collectAsState()
    AuditScreen(container.auditLogService, sessions)
}

@Composable
@Suppress("FunctionName", "UnusedPrivateMember")
private fun EmptyDestination(
    destination: ShellDestination,
    contentPadding: PaddingValues,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .testTag("screen-${destination.route}"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.empty_not_enabled, stringResource(destination.titleRes)),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(destination.emptyStateRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Sessions own their header; all other routes share the same compact visual style. */
@Composable
@Suppress("FunctionName")
private fun ShellTopBar(
    currentDestination: ShellDestination?,
    secondaryTitleRes: Int?,
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
                CompactPageHeader(stringResource(currentDestination.titleRes), onNavigation)
            }
        }
    }
}
