package com.helix.app.goal

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import com.helix.app.ShellDestination
import com.helix.app.chat.ChatService
import kotlinx.coroutines.flow.first

/** Decode only a well-formed Goal route. Stored bindings determine the actual session. */
internal fun goalReminderId(intent: Intent?): String? {
    val uri = intent?.data ?: return null
    val id = uri.pathSegments.singleOrNull()
    return id?.takeIf {
        uri.scheme == "helix" && uri.authority == "goal" &&
            it.isNotBlank() && it == intent.getStringExtra(GoalReminderPayload.KEY_GOAL_ID)
    }
}

@Composable
@Suppress("FunctionName")
internal fun GoalReminderNavigation(
    service: ChatService,
    navController: NavHostController,
) {
    val reminderGoal by service.reminderGoal.collectAsState()
    LaunchedEffect(reminderGoal) {
        if (reminderGoal != null) {
            navController.currentBackStackEntryFlow.first()
            navController.navigate(ShellDestination.Sessions.route) { launchSingleTop = true }
        }
    }
}
