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
internal const val SETTINGS_STORAGE_ROUTE = "settings/storage"
internal const val SETTINGS_AUDIT_ROUTE = "settings/audit"

private val secondaryTitles =
    mapOf(
        "file-location" to R.string.nav_files,
        CONVERSATION_HISTORY_ROUTE to R.string.conversation_history_title,
        CONVERSATION_SEARCH_ROUTE to R.string.conversation_search_title,
        CONVERSATION_SETTINGS_ROUTE to R.string.session_settings_title,
        SETUP_READINESS_ROUTE to R.string.nav_readiness,
        SETUP_CAPABILITIES_ROUTE to R.string.nav_capabilities,
        SETUP_RUNTIME_ROUTE to R.string.setup_runtime_title,
        SETTINGS_DEFAULTS_ROUTE to R.string.settings_defaults_title,
        SETTINGS_PERMISSIONS_ROUTE to R.string.settings_permissions_safety_title,
        SETTINGS_SYSTEM_PERMISSIONS_ROUTE to R.string.settings_system_permissions_title,
        SETTINGS_STORAGE_ROUTE to R.string.settings_storage_title,
        SETTINGS_AUDIT_ROUTE to R.string.settings_diagnostics_title,
    )

@StringRes
internal fun secondaryRouteTitle(route: String): Int? =
    if (route.startsWith("project/")) R.string.nav_projects else secondaryTitles[route]
