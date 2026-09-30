package com.helix.app.settings

import android.content.Context
import com.helix.runtime.quickjs.JsNativeAccessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit user settings only; never a model-facing permission or Runtime execution entry. */
internal class QuickJsAccessService(
    context: Context,
) {
    private val settings = JsNativeAccessSettings(context.applicationContext)

    suspend fun enabled(): Boolean = withContext(Dispatchers.IO) { settings.enabled }

    suspend fun setEnabled(value: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            settings.setEnabled(value)
            settings.enabled
        }
}
