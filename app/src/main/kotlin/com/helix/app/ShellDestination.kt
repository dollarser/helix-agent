package com.helix.app

import androidx.annotation.StringRes

/**
 * The eleven shell destinations (HXA-028; Tasks, Artifacts, Git and Capabilities added by the
 * P0-B product surfaces). [route] is the stable, locale-independent nav key;
 * [titleRes] / [emptyStateRes] are the user-visible string ids resolved at the UI boundary
 * (HXA-069) so the drawer and top bar follow the active app language.
 */
enum class ShellDestination(
    val route: String,
    @StringRes val titleRes: Int,
    @StringRes val emptyStateRes: Int,
) {
    Sessions(
        route = "sessions",
        titleRes = R.string.nav_sessions,
        emptyStateRes = R.string.empty_sessions,
    ),
    Tasks(
        route = "tasks",
        titleRes = R.string.nav_tasks,
        emptyStateRes = R.string.empty_tasks,
    ),
    Artifacts(
        route = "artifacts",
        titleRes = R.string.nav_artifacts,
        emptyStateRes = R.string.empty_artifacts,
    ),
    Git(
        route = "git",
        titleRes = R.string.nav_git,
        emptyStateRes = R.string.empty_git,
    ),
    Files(
        route = "files",
        titleRes = R.string.nav_files,
        emptyStateRes = R.string.empty_files,
    ),
    Browser(
        route = "browser",
        titleRes = R.string.nav_browser,
        emptyStateRes = R.string.empty_browser,
    ),
    Extensions(
        route = "extensions",
        titleRes = R.string.nav_extensions,
        emptyStateRes = R.string.empty_extensions,
    ),
    Capabilities(
        route = "capabilities",
        titleRes = R.string.nav_capabilities,
        emptyStateRes = R.string.empty_capabilities,
    ),
    Permissions(
        route = "permissions",
        titleRes = R.string.nav_permissions,
        emptyStateRes = R.string.empty_permissions,
    ),
    Settings(
        route = "settings",
        titleRes = R.string.nav_settings,
        emptyStateRes = R.string.empty_settings,
    ),
    Audit(
        route = "audit",
        titleRes = R.string.nav_audit,
        emptyStateRes = R.string.empty_audit,
    ),
}
