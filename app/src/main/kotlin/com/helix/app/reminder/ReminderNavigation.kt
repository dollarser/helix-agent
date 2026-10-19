package com.helix.app.reminder

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.helix.app.ShellDestination
import kotlinx.coroutines.flow.first

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
) {
    val request by service.openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request != null) {
            navigation.currentBackStackEntryFlow.first()
            navigation.navigate(ShellDestination.Reminders.route) { launchSingleTop = true }
        }
    }
}
