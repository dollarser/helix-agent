package com.helix.app.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import com.helix.app.ShellDestination
import com.helix.extensions.plugin.PluginTaskIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** A native user click opens an already selected conversation; it grants no task/tool authority. */
internal object PluginTaskNavigation {
    private val pending = MutableStateFlow<PluginTaskIdentity?>(null)

    fun request(task: PluginTaskIdentity) {
        pending.value = task
    }

    @Composable
    @Suppress("FunctionName")
    fun Observe(navController: NavHostController) {
        val task by pending.collectAsState()
        LaunchedEffect(task) {
            val current = task ?: return@LaunchedEffect
            navController.currentBackStackEntryFlow.first()
            navController.navigate(ShellDestination.Sessions.route) { launchSingleTop = true }
            pending.compareAndSet(current, null)
        }
    }
}
