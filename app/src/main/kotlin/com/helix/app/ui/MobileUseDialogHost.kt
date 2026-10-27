package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.helix.app.chat.ChatService
import com.helix.app.plugin.PluginService

@Composable
@Suppress("FunctionName")
internal fun MobileUseDialogHost(
    state: MobileUseDialogState,
    navigation: NavController,
    plugins: PluginService,
    chat: ChatService,
) {
    val request = state.request ?: return
    val entry by navigation.currentBackStackEntryAsState()
    val screen = chat.screen.collectAsStateWithLifecycle()
    val currentSession by remember(screen) { derivedStateOf { screen.value.openSessionId } }
    if (entry == null) return
    val repairPresent =
        remember(navigation, entry, request.repairRoute, request.repairEntryId) {
            if (request.repairRoute.isEmpty()) {
                false
            } else {
                try {
                    navigation.getBackStackEntry(request.repairRoute).id == request.repairEntryId
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
        }
    LaunchedEffect(entry?.id, currentSession, request.id, repairPresent) {
        entry?.let { state.reconcile(it.id, chat.screen.value.openSessionId, repairPresent) }
    }
    val current = {
        state.matches(request.id, navigation.currentBackStackEntry?.id, chat.screen.value.openSessionId)
    }
    if (entry?.id == request.originEntryId && current()) {
        val repair: (String) -> Unit = { route ->
            val source = navigation.currentBackStackEntry
            if (source != null && current()) {
                navigation.navigateFrom(source, route)
                val target = navigation.currentBackStackEntry
                if (target != null && target.id != source.id) state.repair(route, target.id)
            }
        }
        MobileUseSessionDialog(
            plugins,
            request.sessionId,
            request.id,
            current,
            onPermissions = { repair(SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
            onConfigure = { repair(MOBILE_USE_SETTINGS_ROUTE) },
            onDismiss = state::dismiss,
        )
    }
}
