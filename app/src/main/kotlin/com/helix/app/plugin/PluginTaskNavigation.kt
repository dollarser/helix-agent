package com.helix.app.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import com.helix.app.ShellDestination
import com.helix.extensions.plugin.PluginTaskIdentity
import kotlinx.coroutines.flow.MutableStateFlow

/** A native user click opens an already selected conversation; it grants no task/tool authority. */
internal object PluginTaskNavigation {
    private val pending = MutableStateFlow<PluginTaskIdentity?>(null)

    fun request(task: PluginTaskIdentity) {
        pending.value = task
    }

    @Composable
    @Suppress("FunctionName")
    fun Observe(
        navController: NavHostController,
        departure: com.helix.app.ui.NavigationDeparture,
    ) {
        val observedRequest by pending.collectAsState()
        val task = observedRequest
        com.helix.app.ui.RequestedPageNavigation(
            task?.toString(),
            ShellDestination.Sessions.route,
            navController,
            departure,
            onConsumed = { task?.let { pending.compareAndSet(it, null) } },
        )
    }
}
