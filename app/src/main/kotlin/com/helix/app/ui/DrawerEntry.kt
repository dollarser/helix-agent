package com.helix.app.ui

import com.helix.app.R
import com.helix.app.ShellDestination

internal data class DrawerEntry(
    val route: String,
    val titleRes: Int,
    val descriptionRes: Int? = null,
) {
    fun matches(currentRoute: String): Boolean =
        currentRoute == route ||
            (route == ShellDestination.Settings.route && currentRoute == SETTINGS_EXPERTS_ROUTE) ||
            (route == "projects" && currentRoute.startsWith("project/")) ||
            (route != ShellDestination.Settings.route && currentRoute.startsWith("$route/")) ||
            (route == SETTINGS_AUDIT_ROUTE && currentRoute in diagnosticChildRoutes)
}

private val diagnosticChildRoutes = setOf("setup", SETUP_READINESS_ROUTE, SETUP_CAPABILITIES_ROUTE)

internal val settingsDrawerEntries =
    listOf(
        DrawerEntry(ShellDestination.Settings.route, R.string.settings_general_title),
        DrawerEntry(SETTINGS_DEFAULTS_ROUTE, R.string.settings_defaults_title),
        DrawerEntry(SETTINGS_PERMISSIONS_ROUTE, R.string.settings_permissions_safety_title),
        DrawerEntry(SETUP_RUNTIME_ROUTE, R.string.setup_runtime_title),
        DrawerEntry(SETTINGS_STORAGE_ROUTE, R.string.settings_storage_title, R.string.nav_storage_description),
        DrawerEntry(SETTINGS_AUDIT_ROUTE, R.string.settings_diagnostics_title, R.string.nav_diagnostics_description),
    )
