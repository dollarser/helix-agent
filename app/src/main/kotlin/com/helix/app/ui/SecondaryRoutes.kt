package com.helix.app.ui

import androidx.annotation.StringRes
import com.helix.app.R

internal const val SETUP_READINESS_ROUTE = "setup/readiness"
internal const val SETUP_CAPABILITIES_ROUTE = "setup/capabilities"
internal const val SETUP_RUNTIME_ROUTE = "setup/runtime"
internal const val CONVERSATION_HISTORY_ROUTE = "conversation/history"
internal const val CONVERSATION_SEARCH_ROUTE = "conversation/search"
internal const val CONVERSATION_SETTINGS_ROUTE = "conversation/settings"
internal const val SETTINGS_DEFAULTS_ROUTE = "settings/defaults"
internal const val SETTINGS_PERMISSIONS_ROUTE = "settings/permissions"
internal const val SETTINGS_SYSTEM_PERMISSIONS_ROUTE = "settings/permissions/system"
internal const val SETTINGS_AUDIT_ROUTE = "settings/audit"

@StringRes
internal fun secondaryRouteTitle(route: String): Int? =
    when (route) {
        CONVERSATION_HISTORY_ROUTE -> R.string.conversation_history_title
        CONVERSATION_SEARCH_ROUTE -> R.string.conversation_search_title
        CONVERSATION_SETTINGS_ROUTE -> R.string.session_settings_title
        SETUP_READINESS_ROUTE -> R.string.nav_readiness
        SETUP_CAPABILITIES_ROUTE -> R.string.nav_capabilities
        SETUP_RUNTIME_ROUTE -> R.string.setup_runtime_title
        SETTINGS_DEFAULTS_ROUTE -> R.string.settings_defaults_title
        SETTINGS_PERMISSIONS_ROUTE -> R.string.settings_permissions_safety_title
        SETTINGS_SYSTEM_PERMISSIONS_ROUTE -> R.string.settings_system_permissions_title
        SETTINGS_AUDIT_ROUTE -> R.string.settings_diagnostics_title
        else -> null
    }
