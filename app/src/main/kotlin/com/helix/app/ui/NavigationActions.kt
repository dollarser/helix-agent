package com.helix.app.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

internal const val SECTION_ROOT_KEY = "helix.navigation.section-root"

/** Drawer switches replace the active section; nested actions continue to push their own routes. */
internal fun NavController.openSection(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
    currentBackStackEntry?.savedStateHandle?.set(SECTION_ROOT_KEY, true)
}

/** A callback may finish only its originating, resumed page, once. */
internal class NavigationCompletion(
    private val isCurrent: () -> Boolean,
    private val onConsumed: () -> Unit = {},
) {
    private var consumed = false

    fun complete(action: () -> Unit): Boolean {
        if (consumed) return false
        consumed = true
        onConsumed()
        val current = isCurrent()
        if (current) action()
        return current
    }

    fun invalidate() {
        if (consumed) return
        consumed = true
        onConsumed()
    }
}

internal fun NavController.completionFor(entry: NavBackStackEntry): NavigationCompletion =
    entry.lifecycle.navigationCompletion { currentBackStackEntry === entry }

internal fun Lifecycle.navigationCompletion(isCurrent: () -> Boolean = { true }): NavigationCompletion {
    lateinit var observer: androidx.lifecycle.LifecycleEventObserver
    val completion =
        NavigationCompletion(
            isCurrent = { currentState == Lifecycle.State.RESUMED && isCurrent() },
            onConsumed = { removeObserver(observer) },
        )
    observer =
        androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) completion.invalidate()
        }
    addObserver(observer)
    return completion
}

internal fun NavController.returnFrom(entry: NavBackStackEntry) {
    completionFor(entry).complete { popBackStack() }
}

internal fun NavController.navigateFrom(
    entry: NavBackStackEntry,
    route: String,
    options: androidx.navigation.NavOptionsBuilder.() -> Unit = {},
) {
    completionFor(entry).complete {
        navigate(route) {
            launchSingleTop = true
            options()
        }
        currentBackStackEntry?.savedStateHandle?.set(SECTION_ROOT_KEY, false)
    }
}

internal fun NavController.openSectionFrom(
    entry: NavBackStackEntry,
    route: String,
) {
    completionFor(entry).complete { openSection(route) }
}
