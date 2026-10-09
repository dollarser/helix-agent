package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Refresh immediately on foreground entry; no polling while paused or off-screen. */
@Composable
@Suppress("FunctionName")
internal fun ForegroundRefresh(
    key: Any?,
    refresh: suspend () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val action = rememberUpdatedState(refresh)
    LaunchedEffect(owner, key) {
        repeatForegroundRefresh(owner.lifecycle) { action.value() }
    }
}

internal suspend fun repeatForegroundRefresh(
    lifecycle: Lifecycle,
    refresh: suspend () -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
            refresh()
            delay(3_000)
        }
    }
}
