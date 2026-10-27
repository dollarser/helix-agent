package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import com.helix.app.tool.DevicePageTool

/** UI consumes a closed navigation request; it does not execute tools or grant authority. */
@Composable
@Suppress("FunctionName")
internal fun DevicePageNavigation(
    navigation: NavHostController,
    departure: NavigationDeparture,
) {
    val observedRequest by DevicePageTool.pending.collectAsState()
    val request = observedRequest
    RequestedPageNavigation(
        request?.id,
        request?.route.orEmpty(),
        navigation,
        departure,
        onConsumed = { request?.let { DevicePageTool.pending.compareAndSet(it, null) } },
    )
}
