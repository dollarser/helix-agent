package com.helix.runtime.quickjs

import android.content.Context

/** User-owned master grant. Model arguments cannot change this setting. */
class JsNativeAccessSettings(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("quickjs-native-access", Context.MODE_PRIVATE)

    val enabled: Boolean get() = preferences.getBoolean("enabled", false)
    val revision: Long get() = preferences.getLong("revision", 0)

    fun setEnabled(value: Boolean) {
        synchronized(LOCK) {
            check(
                preferences
                    .edit()
                    .putBoolean("enabled", value)
                    .putLong("revision", revision + 1)
                    .commit(),
            ) {
                "Could not save QuickJS native access setting"
            }
        }
    }

    fun allows(expectedRevision: Long): Boolean = enabled && revision == expectedRevision

    private companion object {
        val LOCK = Any()
    }
}
