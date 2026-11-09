package com.helix.app.eval

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager

/** Wait for the framework to consume removal before restoring an already-granted component. */
internal object GrantedAccessibilityReconnect {
    fun awaitRemoved(
        context: Context,
        component: String,
        frameworkState: () -> String,
    ) {
        val expected = requireNotNull(ComponentName.unflattenFromString(component))
        val manager = context.getSystemService(AccessibilityManager::class.java)
        // A disconnected service may already be absent from the bound-service list.
        // Retain a settling interval as well as checking framework state.
        val earliestRestore = SystemClock.elapsedRealtime() + 250
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (true) {
            val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val bindingCleared = frameworkBindingCleared(frameworkState(), component)
            if (SystemClock.elapsedRealtime() >= earliestRestore && bindingCleared &&
                enabled.none { ComponentName.unflattenFromString(it.id) == expected }
            ) {
                return
            }
            check(SystemClock.elapsedRealtime() < deadline) {
                "BLOCKED: framework retained the Accessibility binding; " +
                    "host recovery and non-restarting instrumentation required"
            }
            SystemClock.sleep(100)
        }
    }

    internal fun frameworkBindingCleared(
        state: String,
        component: String,
    ): Boolean {
        val bindings = state.lineSequence().filter { it.trimStart().startsWith("Binding services:") }.toList()
        return bindings.isNotEmpty() && bindings.none { component in it }
    }
}
