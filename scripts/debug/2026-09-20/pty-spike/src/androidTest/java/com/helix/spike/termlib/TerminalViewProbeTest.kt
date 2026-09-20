package com.helix.spike.termlib

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TerminalViewProbeTest : InstrumentationTestCase() {
    fun testViewImeChineseCommitAndDetachAttach() {
        instrumentation.uiAutomation.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1").close()
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, TerminalViewProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as TerminalViewProbeActivity
        try {
            await { activity.hasWindowFocus() && activity.terminal.dimensions.columns > 0 }
            instrumentation.waitForIdleSync()
            screenshot(activity, "terminal.png")
            instrumentation.runOnMainSync { activity.keyboard = true }
            await { activity.imeVisible && imeVisible(activity) }
            instrumentation.runOnMainSync {
                val input = findInput(activity.window.decorView)
                assertNotNull("No terminal IME input view", input)
                val connection = input!!.onCreateInputConnection(EditorInfo())
                assertNotNull(connection)
                assertTrue(connection.commitText("输入中文", 1))
            }
            await { activity.receivedText().contains("输入中文") }
            screenshot(activity, "keyboard.png")
            instrumentation.runOnMainSync { activity.attached = false }
            await { !imeVisible(activity) }
            instrumentation.waitForIdleSync()
            // A detached view must not close its session state.
            instrumentation.runOnMainSync {
                activity.terminal.writeInput("\r\nDETACHED_STATE_保留\r\n".toByteArray())
                activity.keyboard = false
                activity.attached = true
            }
            await { findInput(activity.window.decorView) != null }
            instrumentation.waitForIdleSync()
            screenshot(activity, "reattached.png")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            await { activity.isDestroyed }
        }
    }

    private fun findInput(view: View): View? =
        when {
            view.javaClass.simpleName == "ImeInputView" -> view
            view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findInput(view.getChildAt(it)) }
            else -> null
        }

    private fun screenshot(
        activity: TerminalViewProbeActivity,
        name: String,
    ) {
        var bitmap = instrumentation.uiAutomation.takeScreenshot()
        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (stable < 3 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            val next = instrumentation.uiAutomation.takeScreenshot()
            stable = if (next.sameAs(bitmap)) stable + 1 else 0
            bitmap.recycle()
            bitmap = next
        }
        assertTrue("No stable terminal screenshot", stable >= 3)
        val layoutFits = AtomicBoolean()
        instrumentation.runOnMainSync {
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bounds = activity.terminalBounds
            val bottom = activity.window.decorView.height - maxOf(bars.bottom, ime.bottom)
            println("Terminal bounds=$bounds top=${bars.top} bottom=$bottom")
            layoutFits.set(bounds.top >= bars.top && bounds.height > 0 && bounds.bottom <= bottom + 1)
        }
        assertTrue("Terminal overlaps system bars or keyboard", layoutFits.get())
        val file = File(activity.cacheDir, "terminal-ui/$name")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val greenPixels =
            pixels.count { pixel ->
                Color.green(pixel) > 80 && Color.green(pixel) > Color.red(pixel) * 1.5 &&
                    Color.green(pixel) > Color.blue(pixel) * 1.5
            }
        println(
            "Terminal screenshot $name: greenPixels=$greenPixels " +
                "hardware=${activity.window.decorView.isHardwareAccelerated}",
        )
        bitmap.recycle()
        assertTrue("Missing green terminal glyphs in $name", greenPixels > 40)
    }

    private fun imeVisible(activity: TerminalViewProbeActivity): Boolean {
        val visible = AtomicBoolean()
        instrumentation.runOnMainSync {
            visible.set(
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) ==
                    true,
            )
        }
        return visible.get()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition()) {
            assertTrue("Timed out awaiting terminal view state", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(25)
        }
    }
}
