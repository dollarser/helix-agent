package com.helix.app.knowledge

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in real Chrome public-page share. Never opens a social-app inbox or accepts account setup. */
class PublicBrowserCollectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun publicPageSharesThroughChromeMenuAndAndroidSharesheet(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("helix.publicBrowser")
            require(flag == null || flag in setOf("true", "false"))
            assumeTrue(flag == "true")
            val token = "helix-public-${UUID.randomUUID()}"
            val url = "https://example.com/?fixture=$token"
            val service = (context.applicationContext as HelixApplication).knowledge
            val automation = instrumentation.uiAutomation
            val oldFlags = automation.serviceInfo.flags
            automation.serviceInfo =
                automation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                }
            try {
                openPublicPageShare(url)
                val label =
                    context.packageManager
                        .getActivityInfo(
                            android.content.ComponentName(context, KnowledgeCaptureActivity::class.java),
                            0,
                        ).loadLabel(context.packageManager)
                        .toString()
                waitFor { selectTarget(label) }
                val received =
                    withTimeout(30_000) {
                        service.items.first { rows ->
                            rows.any { source ->
                                source.rawText.contains(token) && source.state != "IMPORTING"
                            }
                        }
                    }
                val row = received.first { it.rawText.contains(token) }
                assertEquals(url, row.sourceUrl)
                assertTrue(row.rawText.contains(token))
                android.util.Log.i("HelixKnowledgeEvidence", "publicChromeShare=received originalUrlRetained=true")
            } finally {
                service.items
                    .first()
                    .filter { it.rawText.contains(token) }
                    .forEach { service.remove(it.id) }
                automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            }
        }

    private suspend fun openPublicPageShare(url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
                .setPackage("com.android.chrome")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitFor { find("com.android.chrome") { it.text?.contains("Example Domain") == true } != null }
        waitFor {
            val menu =
                find("com.android.chrome") {
                    it.viewIdResourceName == "com.android.chrome:id/menu_button"
                }
            click(menu)
        }
        waitFor {
            val row =
                find("com.android.chrome") {
                    it.viewIdResourceName == "com.android.chrome:id/share_menu_id"
                }
            val title =
                row
                    ?.findAccessibilityNodeInfosByViewId("com.android.chrome:id/title")
                    ?.firstOrNull { it.isClickable && it.isEnabled }
            click(title ?: row)
        }
    }

    private var stage = 0

    private suspend fun waitFor(condition: () -> Boolean) {
        stage++
        android.util.Log.i("HelixKnowledgeEvidence", "publicChromeStage=$stage")
        val found =
            kotlinx.coroutines.withTimeoutOrNull(20_000) {
                while (!condition()) delay(100)
                true
            }
        if (found != true) {
            logControlIds()
            error("Public browser share stage $stage unavailable")
        }
    }

    private fun logControlIds() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        val packageName = root.packageName?.toString()
        if (packageName !in setOf("com.android.chrome", "com.android.intentresolver", "android")) return
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        val ids = mutableSetOf<String>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 500) {
            val node = pending.removeFirst()
            node.viewIdResourceName?.let { ids.add(it + ":enabled=" + node.isEnabled + ":click=" + node.isClickable) }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        android.util.Log.i("HelixKnowledgeEvidence", "publicChromeIds package=$packageName ids=$ids")
    }

    private fun find(
        packageName: String,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root == null || root.packageName?.toString() != packageName) return null
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        var result: AccessibilityNodeInfo? = null
        while (pending.isNotEmpty() && visited++ < 800 && result == null) {
            val node = pending.removeFirst()
            if (matches(node)) result = node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return result
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isVisibleToUser || !node.isEnabled) return false
        var target: AccessibilityNodeInfo? = node
        var clicked = false
        while (target != null && !clicked) {
            clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target = target.parent
        }
        return clicked || tap(node)
    }

    private var shareScrolls = 0
    private var lastScroll = 0L

    private fun selectTarget(label: String): Boolean {
        val target =
            find("com.android.intentresolver") {
                it.text?.toString() == label || it.contentDescription?.toString() == label
            }
        if (target?.isVisibleToUser == true) return click(target)
        val now = android.os.SystemClock.uptimeMillis()
        if (shareScrolls < 3 && now - lastScroll > 500) {
            val controlId = if (shareScrolls == 0) "android:id/drag" else "android:id/resolver_list"
            val control = find("com.android.intentresolver") { it.viewIdResourceName == controlId }
            if (control != null) {
                swipeUp(control)
                shareScrolls++
                lastScroll = now
            }
        }
        return false
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val began = android.os.SystemClock.uptimeMillis()
        return !bounds.isEmpty &&
            touch(android.view.MotionEvent.ACTION_DOWN, began, bounds.exactCenterX(), bounds.exactCenterY()) &&
            touch(android.view.MotionEvent.ACTION_UP, began, bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun swipeUp(node: AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect()
        val window = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(window)
        val began = android.os.SystemClock.uptimeMillis()
        val x = bounds.exactCenterX()
        val from = bounds.exactCenterY()
        val to = (window.top + window.height() / 8).toFloat()
        check(touch(android.view.MotionEvent.ACTION_DOWN, began, x, from))
        for (step in 1..10) {
            Thread.sleep(15)
            check(touch(android.view.MotionEvent.ACTION_MOVE, began, x, from + (to - from) * step / 10))
        }
        check(touch(android.view.MotionEvent.ACTION_UP, began, x, to))
    }

    private fun touch(
        action: Int,
        began: Long,
        x: Float,
        y: Float,
    ): Boolean {
        val event = android.view.MotionEvent.obtain(began, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        return try {
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
        } finally {
            event.recycle()
        }
    }
}
