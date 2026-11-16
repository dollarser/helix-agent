package com.helix.app.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Re-read the current configuration so selected plugin names follow app-language changes. */
@Composable
internal fun pluginLabel(name: PluginName): String {
    val locales = LocalConfiguration.current.locales
    return name.displayName(List(locales.size()) { locales[it].toLanguageTag() })
}

@Composable
internal fun expertAbilityLabel(choice: ExpertAbilityChoice): String =
    choice.pluginName?.let { pluginLabel(it) } ?: choice.name
