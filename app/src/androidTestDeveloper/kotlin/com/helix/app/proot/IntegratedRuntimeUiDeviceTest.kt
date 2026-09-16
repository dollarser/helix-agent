package com.helix.app.proot

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.cli.app.ClaudeLoginActivity
import com.helix.runtime.cli.app.CodexLoginActivity
import com.helix.runtime.cli.app.CopilotLoginActivity
import com.helix.runtime.cli.app.GrokLoginActivity
import com.helix.runtime.cli.app.SubscriptionNetworkSettingsActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.runtime.cli.app.R as SubscriptionR

/** Observe actual remote-process UI; ActivityScenario callbacks cannot cross process boundaries. */
class IntegratedRuntimeUiDeviceTest {
    @Test
    fun privateSubscriptionPagesRenderInTheHostApplication() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pages =
            listOf(
                CodexLoginActivity::class.java to SubscriptionR.string.codex_login_title,
                CopilotLoginActivity::class.java to SubscriptionR.string.copilot_login_title,
                ClaudeLoginActivity::class.java to SubscriptionR.string.claude_login_title,
                GrokLoginActivity::class.java to SubscriptionR.string.grok_login_title,
                SubscriptionNetworkSettingsActivity::class.java to SubscriptionR.string.subscription_dns_title,
            )
        for ((activity, title) in pages) {
            context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // API 33+ per-app locales also apply to the remote Activity. The
            // instrumentation target Context may still cache the process-start locale.
            val locales =
                if (Build.VERSION.SDK_INT >= 33) {
                    context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
                } else {
                    context.resources.configuration.locales
                }
            val resourcesContext =
                if (locales.isEmpty) {
                    context
                } else {
                    context.createConfigurationContext(
                        Configuration(context.resources.configuration).apply { setLocales(locales) },
                    )
                }
            val expected = resourcesContext.getString(title)
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(10)
            var visible = false
            while (!visible && System.nanoTime() < deadline) {
                visible = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(expected)
                    ?.isNotEmpty() == true
                if (!visible) Thread.sleep(100)
            }
            assertTrue("Private runtime page did not render: $expected", visible)
        }
    }
}
