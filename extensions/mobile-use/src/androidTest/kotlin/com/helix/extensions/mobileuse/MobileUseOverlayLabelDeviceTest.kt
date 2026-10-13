package com.helix.extensions.mobileuse

import android.app.UiAutomation
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Real native layout/callback assertions; no model, target-app actions or permanent permission. */
class MobileUseOverlayLabelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test
    fun localizedControlsFitAtNormalAndLargeFonts() {
        val original =
            shell("cmd appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
                .substringAfter("SYSTEM_ALERT_WINDOW: ", "default")
                .substringBefore(';')
                .trim()
        require(original in setOf("allow", "deny", "ignore", "default"))
        try {
            shell("cmd appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            for (language in listOf("en", "zh-CN")) {
                for (scale in listOf(1f, 2f)) verify(language, scale)
            }
        } finally {
            shell("cmd appops set ${context.packageName} SYSTEM_ALERT_WINDOW $original")
        }
    }

    private fun verify(
        language: String,
        scale: Float,
    ) {
        val configured =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                    fontScale = scale
                },
            )
        var stopped = 0
        var returned = 0
        var windows: MobileUseOverlayWindows? = null
        try {
            instrumentation.runOnMainSync {
                windows = MobileUseOverlayWindows(configured, { stopped++ }, { returned++ }, { _, _ -> })
                windows!!.show("Fixture", false)
            }
            val labels =
                listOf(R.string.mobile_overlay_take_over, R.string.mobile_overlay_view_chat)
                    .map(configured::getString)
            val deadline = SystemClock.elapsedRealtime() + 5000
            var measured = false
            while (!measured && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    measured =
                        labels.all { label ->
                            views().filterIsInstance<TextView>().any { button ->
                                button.text.toString() == label && button.layout != null
                            }
                        }
                }
                if (!measured) SystemClock.sleep(20)
            }
            assertTrue("Actual overlay did not finish layout", measured)
            instrumentation.runOnMainSync {
                for (label in labels) {
                    val button = views().filterIsInstance<TextView>().single { it.text.toString() == label }
                    assertEquals("$language/$scale/$label wraps", 1, button.layout.lineCount)
                    assertEquals("$language/$scale/$label is truncated", 0, button.layout.getEllipsisCount(0))
                    val availableWidth = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
                    assertTrue(
                        "$language/$scale/$label clips horizontally",
                        button.layout.getLineWidth(0) <= availableWidth,
                    )
                    val availableHeight = button.height - button.compoundPaddingTop - button.compoundPaddingBottom
                    assertTrue("$language/$scale/$label clips vertically", button.layout.height <= availableHeight)
                    assertTrue(button.height >= (48 * configured.resources.displayMetrics.density).toInt())
                    assertTrue(button.performClick())
                }
                assertEquals(1, stopped)
                assertEquals(1, returned)
            }
            println("OVERLAY_LABELS language=$language fontScale=$scale PASS")
        } finally {
            instrumentation.runOnMainSync { windows?.remove() }
        }
    }

    private fun views(): List<View> = WindowInspector.getGlobalWindowViews().flatMap(::descendants)

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) {
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            } else {
                emptyList()
            }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
