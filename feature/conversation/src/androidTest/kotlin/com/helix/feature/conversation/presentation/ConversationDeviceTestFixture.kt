package com.helix.feature.conversation.presentation

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.inspector.WindowInspector
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before

/** Presentation tests belong to the library test APK, never the daily App or its application graph. */
abstract class ConversationDeviceTestFixture(
    private val usesClipboard: Boolean = false,
) {
    private val clipboard = ConversationClipboardFixture()

    @Before
    fun prepareIsolatedConversationFixture() {
        requireConversationTestApplication(ApplicationProvider.getApplicationContext())
        requireConversationTestApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        if (usesClipboard) clipboard.capture()
    }

    @After
    fun restoreConversationFixture() {
        if (usesClipboard) clipboard.restore()
    }
}

private fun requireConversationTestApplication(context: Context) {
    require(context.packageName == "com.helix.feature.conversation.test")
    require(context.applicationContext.javaClass == Application::class.java)
}

/** Preserve the original ClipData instance only in memory, while the isolated test owns focus. */
private class ConversationClipboardFixture {
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
        requireConversationTestApplication(context)
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
