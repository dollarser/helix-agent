package com.helix.ui.shared

import android.content.ActivityNotFoundException

/** A user-requested system UI may be absent or disallowed; neither outcome loses the draft. */
@Suppress("SwallowedException")
fun launchExternalUi(launch: () -> Unit): Boolean =
    try {
        launch()
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
