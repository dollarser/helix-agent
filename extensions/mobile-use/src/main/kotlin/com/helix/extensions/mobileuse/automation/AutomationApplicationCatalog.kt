package com.helix.extensions.mobileuse.automation

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.util.Locale

/** Local settings catalog, not a model tool. Listing an app never grants permission to operate it. */
class AutomationApplicationCatalog(
    context: Context,
) {
    private val packages = context.applicationContext.packageManager

    // Only the Advanced host declares QUERY_ALL_PACKAGES; the final APK verifier checks that boundary.
    @android.annotation.SuppressLint("QueryPermissionsNeeded")
    @Suppress("DEPRECATION") // The int-flags overload also supports the minimum API 29.
    fun load(): List<AutomationApplication> {
        val launchable =
            packages
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                ).mapNotNull { it.activityInfo?.packageName }
                .toSet()
        return packages
            .getInstalledApplications(0)
            .map { app ->
                AutomationApplication(
                    packageName = app.packageName,
                    label = label(app),
                    system = isSystemApplication(app.flags),
                    launchable = app.packageName in launchable,
                    enabled = app.enabled,
                )
            }.distinctBy { it.packageName }
            .sortedWith(applicationOrder)
    }

    @Suppress("SwallowedException") // A missing app label must not hide an otherwise selectable package.
    private fun label(app: ApplicationInfo): String =
        try {
            app.loadLabel(packages).toString().ifBlank { app.packageName }
        } catch (_: RuntimeException) {
            app.packageName
        }

    companion object {
        fun isSystemApplication(flags: Int): Boolean =
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
}

data class AutomationApplication(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val launchable: Boolean,
    val enabled: Boolean,
    val available: Boolean = true,
)

enum class AutomationApplicationFilter { ALL, USER, SYSTEM, SELECTED }

/** Pure picker projection. Search and refresh never drop hidden or previously selected identities. */
object AutomationApplicationChoices {
    fun visible(
        catalog: List<AutomationApplication>,
        selected: Set<String>,
        query: String,
        filter: AutomationApplicationFilter,
        permittedPackages: Set<String>? = null,
    ): List<AutomationApplication> {
        val known = catalog.associateBy { it.packageName }
        val missing =
            (selected - known.keys).map {
                AutomationApplication(
                    it,
                    it,
                    system = false,
                    launchable = false,
                    enabled = false,
                    available = false,
                )
            }
        val terms =
            query
                .trim()
                .lowercase(Locale.ROOT)
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)
        return (known.values + missing)
            .filter { app ->
                val inScope = permittedPackages == null || app.packageName in permittedPackages
                val name = app.label.lowercase(Locale.ROOT)
                val identity = app.packageName.lowercase(Locale.ROOT)
                val matchesSearch = terms.all { name.contains(it) || identity.contains(it) }
                inScope && matchesFilter(app, selected, filter) && matchesSearch
            }.sortedWith(applicationOrder)
    }

    fun toggle(
        selected: Set<String>,
        app: AutomationApplication,
        single: Boolean = false,
    ): Set<String> =
        when {
            app.packageName in selected -> selected - app.packageName
            !app.available -> selected
            single -> setOf(app.packageName)
            else -> selected + app.packageName
        }

    private fun matchesFilter(
        app: AutomationApplication,
        selected: Set<String>,
        filter: AutomationApplicationFilter,
    ): Boolean =
        when (filter) {
            AutomationApplicationFilter.ALL -> true
            AutomationApplicationFilter.USER -> app.available && !app.system
            AutomationApplicationFilter.SYSTEM -> app.available && app.system
            AutomationApplicationFilter.SELECTED -> app.packageName in selected
        }
}

private val applicationOrder =
    compareBy<AutomationApplication> { !it.available }
        .thenBy { it.system }
        .thenBy { it.label.lowercase(Locale.ROOT) }
        .thenBy { it.packageName }
