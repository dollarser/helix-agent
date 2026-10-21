package com.helix.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The native subscription home only: no provider login button or installation action is pressed. */
class SubscriptionPresentationDeviceTest {
    @Test fun nativeSubscriptionHomeHasReadableActionsAndTouchTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".asrvalidation"))
        context.startActivity(
            Intent()
                .setClassName(context.packageName, "com.helix.runtime.cli.app.CliRuntimeHomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val action = awaitNativeAction()
            val bounds = Rect()
            action.getBoundsInScreen(bounds)
            assertTrue(action.isVisibleToUser && action.packageName == context.packageName)
            assertTrue(bounds.height() >= (48 * context.resources.displayMetrics.density).toInt())
            if (InstrumentationRegistry.getArguments().getString("helix.layout.capture") == "true") {
                captureNativeHome(context.cacheDir)
            }
        } finally {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun awaitNativeAction(): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node =
                automation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Codex")
                    ?.firstOrNull { it.isClickable && it.text?.toString() == "Codex" }
            if (node != null) return node
            Thread.sleep(50)
        }
        error("Native subscription action did not become visible")
    }

    private fun captureNativeHome(cache: File) {
        val bitmap = stableChatLayoutScreenshot()
        try {
            val path = File(cache, "hxa147-layout/integration-subscription.png")
            path.parentFile?.mkdirs()
            path.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
