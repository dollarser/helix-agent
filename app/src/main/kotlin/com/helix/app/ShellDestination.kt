package com.helix.app

import androidx.annotation.StringRes

/**
 * Shell destinations. The session-scoped Git route is reached from conversation actions, not
 * the global drawer. Secondary setup/settings pages have their own routes. [route] is the stable,
 * locale-independent nav key;
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
    Projects(
        route = "projects",
        titleRes = R.string.nav_projects,
        emptyStateRes = R.string.project_empty,
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
    Models(
        route = "models",
        titleRes = R.string.nav_models,
        emptyStateRes = R.string.empty_models,
    ),
    Extensions(
        route = "extensions",
        titleRes = R.string.nav_extensions,
        emptyStateRes = R.string.empty_extensions,
    ),
    Setup(
        route = "setup",
        titleRes = R.string.nav_setup,
        emptyStateRes = R.string.empty_setup,
    ),
    Settings(
        route = "settings",
        titleRes = R.string.settings_general_title,
        emptyStateRes = R.string.empty_settings,
    ),
    Terminal(
        route = "terminal",
        titleRes = R.string.nav_terminal,
        emptyStateRes = R.string.empty_terminal,
    ),
}
