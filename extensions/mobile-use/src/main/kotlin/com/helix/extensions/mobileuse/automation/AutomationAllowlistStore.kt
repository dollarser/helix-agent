package com.helix.extensions.mobileuse.automation

import android.content.Context

/**
 * User-owned application selection. Empty selections cannot start scoped automation;
 * whole-phone access is a separate explicit user grant. The model never mutates this store.
 */
interface AutomationAllowlistStore {
    fun packages(): Set<String>

    fun replace(packages: Set<String>): Set<String>
}

/** SharedPreferences-backed allowlist used by the developer build's permission center. */
class SharedPreferencesAutomationAllowlistStore(
    context: Context,
) : AutomationAllowlistStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun packages(): Set<String> =
        preferences.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().filterTo(sortedSetOf()) {
            AndroidPackageName.isValid(it)
        }

    override fun replace(packages: Set<String>): Set<String> {
        require(packages.all(AndroidPackageName::isValid)) { "allowlist contains an invalid Android package name" }
        val canonical = packages.toSortedSet()
        check(preferences.edit().putStringSet(KEY_PACKAGES, canonical).commit()) {
            "failed to persist the Accessibility automation allowlist"
        }
        return canonical
    }

    private companion object {
        const val PREFERENCES_NAME = "helix_automation_allowlist"
        const val KEY_PACKAGES = "packages"
    }
}
