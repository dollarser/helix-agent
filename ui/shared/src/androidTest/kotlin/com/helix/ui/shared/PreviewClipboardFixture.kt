package com.helix.ui.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry

/** Keep the original clipboard only in this test instance and restore it before the activity closes. */
internal class PreviewClipboardFixture {
    private var original: ClipData? = null
    private var captured = false

    fun capture() {
        withFocusedWindow { clipboard ->
            original = clipboard.primaryClip
            captured = true
        }
    }

    fun restore() {
        if (!captured) return
        withFocusedWindow { clipboard ->
            val saved = original
            if (saved == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(saved)
            original = null
            captured = false
        }
    }

    private fun withFocusedWindow(action: (ClipboardManager) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        requireSharedTestApplication(context)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var completed = false
            instrumentation.runOnMainSync {
                if (WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() }) {
                    action(requireNotNull(context.getSystemService(ClipboardManager::class.java)))
                    completed = true
                }
            }
            if (completed) return
            Thread.sleep(20)
        }
        error("Clipboard fixture requires the isolated test activity to be in the foreground")
    }
}
