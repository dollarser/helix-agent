package com.helix.app.runtimebridge

import android.content.Context

/** Default-on user setting; runtime or Skill metadata cannot override an explicit revocation. */
internal class HostToolBridgeSettings(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("host-tool-bridge", Context.MODE_PRIVATE)
    val enabled: Boolean get() = preferences.getBoolean("enabled", true)
    val revision: Long get() = preferences.getLong("revision", 0)

    @android.annotation.SuppressLint("UseKtx") // The checked commit result is required for truthful grant persistence.
    fun setEnabled(value: Boolean) {
        synchronized(LOCK) {
            check(
                preferences
                    .edit()
                    .putBoolean("enabled", value)
                    .putLong("revision", revision + 1)
                    .commit(),
            ) {
                "Could not save script tool delegation setting"
            }
        }
    }

    fun allows(expectedRevision: Long): Boolean = enabled && revision == expectedRevision

    private companion object {
        val LOCK = Any()
    }
}
