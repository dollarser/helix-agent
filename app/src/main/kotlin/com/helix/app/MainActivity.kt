package com.helix.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.helix.app.allfiles.AllFilesModule
import com.helix.app.ui.AuditScreen
import com.helix.app.ui.ChatScreen
import com.helix.app.ui.FilesScreen
import com.helix.app.ui.FirstLaunchNoticeScreen
import com.helix.app.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as HelixApplication).appContainer
        setContent { HelixApp(container) }
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
        MaterialTheme {
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route ?: repository.initialDestination.route
    val currentDestination =
        repository.destinations.firstOrNull { it.route == currentRoute }
            ?: repository.initialDestination

    MaterialTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        text = "Helix",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    )
                    repository.destinations.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.title) },
                            selected = destination.route == currentRoute,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    popUpTo(repository.initialDestination.route)
                                }
                                scope.launch { drawerState.close() }
                            },
                            modifier =
                                Modifier
                                    .padding(horizontal = 12.dp)
                                    .testTag("navigation-${destination.route}"),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(currentDestination.title) },
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("open-navigation"),
                            ) {
                                Text(
                                    text = "☰",
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
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
                            when (destination) {
                                ShellDestination.Sessions -> {
                                    ChatScreen(container.chatService, container.providerService)
                                }

                                ShellDestination.Settings -> {
                                    SettingsScreen(container.profileStore, container.providerService)
                                }

                                ShellDestination.Audit -> {
                                    AuditScreenDestination(container)
                                }

                                // HXA-046: the file-management screen over the always-available
                                // sources (Workspace, always; developer all-files roots, read-only).
                                ShellDestination.Files -> {
                                    FilesScreen(container.fileManager, container.safTree)
                                }

                                // HXA-045: the all-files consent screen lives in the developer
                                // flavor; the consumer build keeps the honest empty state.
                                ShellDestination.Permissions -> {
                                    if (AllFilesModule.AVAILABLE) {
                                        AllFilesModule.render(container.profileStore)
                                    } else {
                                        EmptyDestination(destination, PaddingValues(24.dp))
                                    }
                                }

                                else -> {
                                    EmptyDestination(destination, PaddingValues(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
@Suppress("FunctionName")
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
                text = "${destination.title}功能尚未启用",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = destination.emptyState,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
