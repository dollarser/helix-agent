package com.helix.app.files

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Shared exact-folder DocumentsUI controls; no grant is persisted by this helper. */
internal class SystemSafPickerControls {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun selectExactFolder(folder: String) {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        awaitNode { it.text?.toString() == folder }
        val select =
            awaitNode {
                it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                    it.viewIdResourceName == "android:id/button1"
            }
        assertTrue(select.isEnabled)
        assertTrue(select.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val allow =
            awaitNode {
                it.viewIdResourceName == "android:id/button1" &&
                    it.text?.toString() in setOf("Allow", "ALLOW", "允许")
            }
        val confirmation = requireNotNull(automation.rootInActiveWindow)
        assertTrue(
            "Grant confirmation must name the exact approved folder",
            find(confirmation) {
                it.viewIdResourceName == "android:id/message" && it.text?.toString()?.contains(folder) == true
            } != null,
        )
        assertTrue(allow.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString()?.endsWith(".documentsui") == true) {
                find(root, predicate)?.let { return it }
            }
            Thread.sleep(100)
        }
        error("Expected exact-folder picker control did not become available")
    }

    fun closeOwnedPicker() {
        val automation = instrumentation.uiAutomation
        repeat(5) {
            val owner = automation.rootInActiveWindow?.packageName?.toString()
            if (owner != null && !owner.endsWith(".documentsui")) return
            if (owner != null) {
                assertTrue(
                    automation.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                    ),
                )
            }
            Thread.sleep(200)
        }
        val owner = automation.rootInActiveWindow?.packageName?.toString()
        assertTrue("Owned picker must be closed", owner != null && !owner.endsWith(".documentsui"))
    }

    @Suppress("ReturnCount") // Recursive traversal stops at the first matching platform node.
    private fun find(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { find(it, predicate) }?.let { return it }
        }
        return null
    }
}
