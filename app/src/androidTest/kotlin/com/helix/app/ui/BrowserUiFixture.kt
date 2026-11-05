package com.helix.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID

/** Browser UI tests must never restore or overwrite the daily browser's session/preferences. */
internal fun browserTestContext(): Context {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val files = File(base.cacheDir, "browser-ui-${UUID.randomUUID()}").apply { mkdirs() }
    return object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = files
    }
}
