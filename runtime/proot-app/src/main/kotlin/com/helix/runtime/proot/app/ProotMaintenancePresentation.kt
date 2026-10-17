package com.helix.runtime.proot.app

import android.content.Context

/** UI receipt only. Installed state is always read from the runtime files, never inferred here. */
internal object ProotMaintenancePresentation {
    private var active = false
    private var persistenceFailed = false

    @Synchronized
    fun begin(
        context: Context,
        message: String,
    ): Boolean {
        if (active) return false
        persistenceFailed =
            !store(context)
                .edit()
                .putBoolean("running", true)
                .putString("message", message)
                .remove("details")
                .commit()
        active = !persistenceFailed
        return active
    }

    @Synchronized
    fun finish(
        context: Context,
        message: String,
        details: String = "",
    ) {
        persistenceFailed =
            !store(context)
                .edit()
                .putString("details", details)
                .putBoolean("running", false)
                .putString("message", message)
                .commit()
        active = false
    }

    @Synchronized
    fun busy(): Boolean = active

    @Synchronized
    fun message(context: Context): String {
        val stored = store(context)
        return if (persistenceFailed) {
            context.getString(R.string.proot_maintenance_unsaved)
        } else if (stored.getBoolean("running", false) && !active) {
            context.getString(R.string.proot_maintenance_interrupted)
        } else {
            stored.getString("message", "").orEmpty()
        }
    }

    fun details(context: Context): String = store(context).getString("details", "").orEmpty()

    private fun store(context: Context) = context.getSharedPreferences("proot-maintenance-ui", Context.MODE_PRIVATE)
}
