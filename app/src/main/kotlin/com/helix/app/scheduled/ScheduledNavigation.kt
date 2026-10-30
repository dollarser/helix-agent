package com.helix.app.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.helix.app.ShellDestination
import com.helix.app.ui.NavigationDeparture
import com.helix.app.ui.RequestedPageNavigation

@Composable
@Suppress("FunctionName")
internal fun ScheduledNavigation(
    service: ScheduledAgentService?,
    navigation: NavHostController,
    departure: NavigationDeparture,
) {
    val source = service ?: return
    val request by source.openRequest.collectAsStateWithLifecycle()
    val entry by navigation.currentBackStackEntryFlow.collectAsStateWithLifecycle(initialValue = null)
    if (entry?.destination?.route != ShellDestination.Reminders.route) {
        RequestedPageNavigation(
            request?.requestId,
            ShellDestination.Reminders.route,
            navigation,
            departure,
            onCancelled = { source.openRequest.compareAndSet(request, null) },
        )
    }
}
