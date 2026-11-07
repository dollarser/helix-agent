package com.helix.ui.shared

import android.app.Application
import android.content.Context

/** Component fixtures belong only to the library test APK, never the daily app or its state. */
internal fun requireSharedTestApplication(context: Context) {
    require(context.packageName == "com.helix.ui.shared.test")
    require(context.applicationContext.javaClass == Application::class.java)
}
