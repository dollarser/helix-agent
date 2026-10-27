package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import kotlinx.coroutines.flow.first

/** External open requests are consumed once; cancelling a draft prompt must not replay on rotation. */
@Composable
@Suppress("FunctionName")
internal fun RequestedPageNavigation(
    requestId: String?,
    route: String,
    navigation: NavController,
    departure: NavigationDeparture,
    onConsumed: () -> Unit = {},
    onCancelled: () -> Unit = onConsumed,
) {
    var handled by rememberSaveable { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(requestId)
    val consume by rememberUpdatedState(onConsumed)
    val cancel by rememberUpdatedState(onCancelled)
    LaunchedEffect(requestId) {
        val id = requestId
        if (id == null) {
            handled = null
            return@LaunchedEffect
        }
        if (handled == id) return@LaunchedEffect
        val source = navigation.currentBackStackEntryFlow.first()
        handled = id
        val consumed = consume
        val rejected = cancel
        departure.request({ latest == id && navigation.currentBackStackEntry === source }, cancelled = rejected) {
            navigation.openSection(route)
            consumed()
        }
    }
}
