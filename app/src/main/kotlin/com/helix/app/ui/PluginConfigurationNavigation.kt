package com.helix.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.helix.app.R
import com.helix.app.connector.MOBILE_USE_PLUGIN_ID
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSetupReason

private const val PLUGIN_CONFIGURATION_ROUTE = "extensions/plugin-configuration/{pluginId}"

internal fun NavController.configurePluginFrom(
    source: NavBackStackEntry,
    pluginId: String,
    reason: PluginSetupReason?,
) {
    val generic = "extensions/plugin-configuration/${Uri.encode(pluginId)}"
    val route =
        if (pluginId == MOBILE_USE_PLUGIN_ID) {
            when (reason) {
                PluginSetupReason.MOBILE_BACKEND -> SETTINGS_SYSTEM_PERMISSIONS_ROUTE
                PluginSetupReason.MOBILE_CONFIGURATION -> MOBILE_USE_SETTINGS_ROUTE
                else -> generic
            }
        } else {
            generic
        }
    navigateFrom(source, route)
}

internal fun NavGraphBuilder.pluginConfigurationDestination(
    nav: NavController,
    service: PluginService,
) {
    composable(PLUGIN_CONFIGURATION_ROUTE) { source ->
        val id = requireNotNull(source.arguments?.getString("pluginId"))
        var requested by rememberSaveable { mutableStateOf(true) }
        Column(Modifier.fillMaxSize().indicatedVerticalScroll(rememberScrollState())) {
            TextButton({
                nav.returnFrom(source)
            }, Modifier.testTag("plugin-configuration-back")) { Text(stringResource(R.string.common_back)) }
            com.helix.app.connector.ConnectorSection(
                service,
                showImport = false,
                showIntroduction = false,
                onlyPluginId = id,
                configurePluginId = id.takeIf { requested },
                onConfigurationShown = { requested = false },
                onPluginSettings = { nav.navigateFrom(source, MOBILE_USE_SETTINGS_ROUTE) },
                onSystemPermissions = { nav.navigateFrom(source, SETTINGS_SYSTEM_PERMISSIONS_ROUTE) },
            )
        }
    }
}
