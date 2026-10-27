package com.helix.app.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

internal const val MOBILE_USE_SETTINGS_ROUTE = "extensions/mobile-use/settings"

/** One global plugin configuration entry; session enablement is a source-bound dialog. */
internal fun NavGraphBuilder.mobileUseSettingsDestination(nav: NavController) {
    composable(MOBILE_USE_SETTINGS_ROUTE) { source ->
        MobileUseSettingsScreen(
            onPermissions = { nav.navigateFrom(source, SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
            onBack = { nav.returnFrom(source) },
            onSaved = { nav.returnFrom(source) },
        )
    }
}
