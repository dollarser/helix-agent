package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val LocalUserPageHost = staticCompositionLocalOf { false }

/** Keep account destinations in one window; each visible page handles its own back action. */
@Composable
@Suppress("FunctionName")
internal fun UserPageHost(
    open: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!open) return
    if (LocalUserPageHost.current) {
        content()
    } else {
        Dialog({}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            CompositionLocalProvider(LocalUserPageHost provides true) {
                Box { content() }
            }
        }
    }
}
