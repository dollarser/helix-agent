package com.helix.app.reminder

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.helix.app.ShellDestination

internal fun localReminderId(intent: Intent): String? =
    intent.data?.let { uri ->
        uri.pathSegments.singleOrNull()?.takeIf {
            uri.scheme == "helix" && uri.authority == "reminder" &&
                runCatching { java.util.UUID.fromString(it) }.isSuccess
        }
    }

@Composable
@Suppress("FunctionName")
internal fun ReminderNavigation(
    service: ReminderService,
    navigation: NavHostController,
    departure: com.helix.app.ui.NavigationDeparture,
) {
    val observedRequest by service.openRequest.collectAsStateWithLifecycle()
    val request = observedRequest
    val entry by navigation.currentBackStackEntryFlow.collectAsStateWithLifecycle(initialValue = null)
    if (entry?.destination?.route != ShellDestination.Reminders.route) {
        com.helix.app.ui.RequestedPageNavigation(
            request,
            ShellDestination.Reminders.route,
            navigation,
            departure,
            onCancelled = { service.openRequest.compareAndSet(request, null) },
        )
    }
}
