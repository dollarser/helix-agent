package com.helix.app.ui

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.runtime.cli.app.AntigravityLoginActivity
import com.helix.runtime.cli.app.BuildConfig
import com.helix.runtime.cli.app.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Observe the real private-process screen through accessibility; never move it into the test process. */
class AntigravityConfigurationDeviceTest {
    @Test fun defaultBuildOffersLoginWithoutStartingAuthorization() {
        assertTrue("Use the default acceptance APK", BuildConfig.ANTIGRAVITY_CLIENT_ID.isNotEmpty())
        assertTrue("Use the default acceptance APK", BuildConfig.ANTIGRAVITY_CLIENT_SECRET.isNotEmpty())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, AntigravityLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val deadline = SystemClock.uptimeMillis() + 10000
            var explanation: AccessibilityNodeInfo? = null
            while (explanation == null && SystemClock.uptimeMillis() < deadline) {
                explanation =
                    find(
                        instrumentation.uiAutomation.rootInActiveWindow,
                        context.getString(R.string.antigravity_logged_out),
                    )
                if (explanation == null) SystemClock.sleep(100)
            }
            assertNotNull("Opening the screen must leave the account logged out", explanation)
            val login =
                find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.antigravity_login))
            assertNotNull(login)
            assertTrue(requireNotNull(login).isEnabled)
        } finally {
            context.startActivity(
                Intent(
                    context,
                    MainActivity::class.java,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        text: String,
    ): AccessibilityNodeInfo? {
        if (node == null || node.text?.toString() == text) return node
        return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it), text) }
    }
}
