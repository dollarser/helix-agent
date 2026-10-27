package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** One active page may defer leaving until its own draft handling has completed. */
internal class NavigationDeparture(
    private val onNavigate: () -> Unit = {},
) {
    private var owner: Any? = null
    private var prepare: (((Boolean) -> Unit) -> Unit)? = null
    private var pending: Any? = null

    fun register(
        token: Any,
        action: ((Boolean) -> Unit) -> Unit,
    ) {
        if (owner !== token) pending = null
        owner = token
        prepare = action
    }

    fun unregister(token: Any) {
        if (owner !== token) return
        owner = null
        prepare = null
        pending = null
    }

    fun request(
        isCurrent: () -> Boolean,
        cancelled: () -> Unit = {},
        navigate: () -> Unit,
    ) {
        if (pending != null || !isCurrent()) {
            cancelled()
            return
        }
        val handler = prepare
        if (handler == null) {
            onNavigate()
            navigate()
            return
        }
        val token = Any()
        pending = token
        handler { accepted ->
            if (pending === token) {
                pending = null
                if (accepted && isCurrent()) {
                    onNavigate()
                    navigate()
                } else {
                    cancelled()
                }
            }
        }
    }
}

internal val LocalNavigationDeparture = staticCompositionLocalOf<NavigationDeparture?> { null }

/** Register only while this page owns foreground navigation, never from an outgoing composition. */
@Composable
@Suppress("FunctionName")
internal fun NavigationLeaveGuard(prepare: ((Boolean) -> Unit) -> Unit) {
    val departure = LocalNavigationDeparture.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current = rememberUpdatedState(prepare)
    DisposableEffect(departure, lifecycle) {
        val token = Any()

        fun update() {
            if (lifecycle.currentState == Lifecycle.State.RESUMED) {
                departure?.register(token) { current.value(it) }
            } else {
                departure?.unregister(token)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            departure?.unregister(token)
        }
    }
}
