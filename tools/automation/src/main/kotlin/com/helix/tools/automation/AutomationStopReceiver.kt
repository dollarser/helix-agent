package com.helix.tools.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit, non-exported target of the foreground notification's immediate stop action. */
class AutomationStopReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent?.action == HelixAccessibilityService.ACTION_STOP) {
            AutomationServiceController.stop(AutomationStopReason.USER_STOP)
        }
    }
}
