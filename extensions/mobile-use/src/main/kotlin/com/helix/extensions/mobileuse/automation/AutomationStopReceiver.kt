package com.helix.extensions.mobileuse.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.helix.extensions.mobileuse.R

/** Explicit, non-exported target of the foreground notification's immediate stop action. */
class AutomationStopReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_RUNTIME = "runtime"
    }

    // Storage and Android shutdown failures are surfaced here, never turned into a successful stop.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent?.action == HelixAccessibilityService.ACTION_STOP) {
            try {
                AutomationServiceController.stopFromNotification(
                    intent.getStringExtra(EXTRA_CONVERSATION),
                    intent.getStringExtra(EXTRA_SCOPE),
                    intent.getStringExtra(EXTRA_RUNTIME),
                )
            } catch (failure: RuntimeException) {
                android.util.Log.e("MobileUse", "Could not finish task stop", failure)
                context?.let {
                    android.widget.Toast
                        .makeText(
                            it,
                            R.string.automation_revoke_failed,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }
}
