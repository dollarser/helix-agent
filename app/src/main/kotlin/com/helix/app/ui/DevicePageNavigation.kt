package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import com.helix.app.tool.DevicePageTool
import kotlinx.coroutines.flow.first

/** UI consumes a closed navigation request; it does not execute tools or grant authority. */
@Composable
@Suppress("FunctionName")
internal fun DevicePageNavigation(navigation: NavHostController) {
    val request by DevicePageTool.pending.collectAsState()
    LaunchedEffect(request) {
        val current = request ?: return@LaunchedEffect
        navigation.currentBackStackEntryFlow.first()
        navigation.navigate(current.route) { launchSingleTop = true }
        DevicePageTool.pending.compareAndSet(current, null)
    }
}
