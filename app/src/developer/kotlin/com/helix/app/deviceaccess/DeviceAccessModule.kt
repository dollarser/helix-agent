package com.helix.app.deviceaccess

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Host system permissions only; independent of plugin registration and configuration. */
internal object DeviceAccessModule {
    const val AVAILABLE: Boolean = true

    @Composable
    @Suppress("FunctionName")
    fun Permissions() {
        val context = LocalContext.current
        AccessibilityAuthorization(context)
        ShizukuAuthorization(context)
        RootAuthorization(context)
    }
}
