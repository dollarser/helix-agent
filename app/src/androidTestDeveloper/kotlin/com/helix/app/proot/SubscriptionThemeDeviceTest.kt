package com.helix.app.proot

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.cli.app.ClaudeLoginActivity
import com.helix.runtime.cli.app.CliRuntimeHomeActivity
import com.helix.runtime.cli.app.CodexLoginActivity
import com.helix.runtime.cli.app.CopilotLoginActivity
import com.helix.runtime.cli.app.GrokLoginActivity
import com.helix.runtime.cli.app.SubscriptionNetworkSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import com.helix.runtime.cli.app.R as SubscriptionR

/** Real :subscriptions pages, without starting OAuth or any model request. */
class SubscriptionThemeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun subscriptionContentUsesTheActualSystemTheme() {
        val night =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        InstrumentationRegistry.getArguments().getString("expectedNight")?.let {
            require(it == "yes" || it == "no")
            assertEquals(it == "yes", night)
        }
        val pages =
            listOf(
                CliRuntimeHomeActivity::class.java to SubscriptionR.string.subscription_app_name,
                CodexLoginActivity::class.java to SubscriptionR.string.codex_login_title,
                ClaudeLoginActivity::class.java to SubscriptionR.string.claude_login_title,
                CopilotLoginActivity::class.java to SubscriptionR.string.copilot_login_title,
                GrokLoginActivity::class.java to SubscriptionR.string.grok_login_title,
                SubscriptionNetworkSettingsActivity::class.java to SubscriptionR.string.subscription_dns_title,
            )
        for ((activity, title) in pages) {
            context.startActivity(Intent(context, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitTitle(title)
            assertPagePalette("${activity.simpleName}-$night")
            SubscriptionWindowTheme.verify(activity.name, night)
        }
    }

    private fun assertPagePalette(name: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        var matched = false
        while (!matched && android.os.SystemClock.elapsedRealtime() < deadline) {
            val frame = stableFrame()
            try {
                matched = matchesPalette(frame)
                if (matched) saveFrame(frame, name)
            } finally {
                frame.recycle()
            }
            if (!matched) Thread.sleep(100)
        }
        assertTrue("Actual subscription content has wrong palette: $name", matched)
    }

    private fun stableFrame(): Bitmap {
        var previous = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var unchanged = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
            val frame = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            unchanged = if (frame.sameAs(previous)) unchanged + 1 else 0
            previous.recycle()
            if (unchanged >= 3) return frame
            previous = frame
        }
        previous.recycle()
        error("Subscription rendering did not stabilize")
    }

    private fun saveFrame(
        frame: Bitmap,
        name: String,
    ) {
        val directory = File(context.cacheDir, "subscription-theme").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun awaitTitle(id: Int) {
        val locales =
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            } else {
                context.resources.configuration.locales
            }
        val localized =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { if (!locales.isEmpty) setLocales(locales) },
            )
        val title = localized.getString(id)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText(title)
                    ?.isNotEmpty() == true
            ) {
                return
            }
            Thread.sleep(100)
        }
        error("Subscription page did not render: $title")
    }

    private fun matchesPalette(frame: Bitmap): Boolean {
        val background = context.getColor(SubscriptionR.color.subscription_background)
        val surface = context.getColor(SubscriptionR.color.subscription_surface)
        val text = context.getColor(SubscriptionR.color.subscription_text)
        var surfaces = 0
        var foreground = 0
        var samples = 0
        for (y in frame.height / 10 until frame.height * 9 / 10 step 3) {
            for (x in frame.width / 10 until frame.width * 9 / 10 step 3) {
                val pixel = frame.getPixel(x, y)
                if (near(pixel, background) || near(pixel, surface)) surfaces++
                if (near(pixel, text)) foreground++
                samples++
            }
        }
        return surfaces > samples / 2 && foreground > 30
    }

    private fun near(
        a: Int,
        b: Int,
    ): Boolean =
        abs(Color.red(a) - Color.red(b)) <= 3 &&
            abs(Color.green(a) - Color.green(b)) <= 3 &&
            abs(Color.blue(a) - Color.blue(b)) <= 3
}
