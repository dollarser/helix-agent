package com.helix.tools.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Test-only bridge that executes in the application component classloader. Android library
 * instrumentation uses a separate classloader, so reading a Kotlin singleton directly from the
 * test would observe a second instance rather than the one used by [HelixAccessibilityService].
 */
class AutomationTestControlReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val center = AutomationPermissionCenter(context)
        val result =
            when (intent.action) {
                ACTION_PROBE -> {
                    center.serviceState().name
                }

                ACTION_REPLACE_ALLOWLIST -> {
                    center.replaceAllowlist(intent.getStringArrayExtra(EXTRA_PACKAGES).orEmpty().toSet())
                    RESULT_OK
                }

                ACTION_START -> {
                    center
                        .startSession(intent.getStringArrayExtra(EXTRA_PACKAGES).orEmpty().toSet())
                        .status.name
                }

                ACTION_STOP -> {
                    center.stopSession()
                    RESULT_OK
                }

                ACTION_DISABLE_SERVICE -> {
                    center.disableService()
                    RESULT_OK
                }

                else -> {
                    RESULT_UNKNOWN_ACTION
                }
            }
        val active = center.activeSession()
        check(
            context
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NONCE, intent.getLongExtra(EXTRA_NONCE, -1))
                .putString(KEY_RESULT, result)
                .putBoolean(KEY_ACTIVE, active != null)
                .commit(),
        ) {
            "failed to persist the automation device-test bridge result"
        }
    }

    companion object {
        const val ACTION_PROBE = "com.helix.tools.automation.test.PROBE"
        const val ACTION_REPLACE_ALLOWLIST = "com.helix.tools.automation.test.REPLACE_ALLOWLIST"
        const val ACTION_START = "com.helix.tools.automation.test.START"
        const val ACTION_STOP = "com.helix.tools.automation.test.STOP"
        const val ACTION_DISABLE_SERVICE = "com.helix.tools.automation.test.DISABLE_SERVICE"
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_PACKAGES = "packages"
        const val PREFERENCES_NAME = "automation_test_control"
        const val KEY_NONCE = "nonce"
        const val KEY_RESULT = "result"
        const val KEY_ACTIVE = "active"
        private const val RESULT_OK = "OK"
        private const val RESULT_UNKNOWN_ACTION = "UNKNOWN_ACTION"
    }
}
