package com.helix.feature.browser

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.instrumentation
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.onMain

/** Fresh, window-bound nodes; no global node cache or retries beyond the caller's budget. */
internal object AutofillInputProbe {
    @Suppress("NestedBlockDepth") // window→root→node traversal is inherently nested
    fun focus(
        view: WebView,
        cycleDeadlineMs: Long,
    ) {
        val deadline = minOf(cycleDeadlineMs, SystemClock.elapsedRealtime() + 10_000)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        var staleWindows = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = onMain { view.isAttachedToWindow && view.hasWindowFocus() }
            if (ready) {
                onMain { view.requestFocus() }
                val node =
                    try {
                        findInput()
                    } catch (failure: IllegalStateException) {
                        if (!failure.message.orEmpty().contains("there is no mView")) throw failure
                        staleWindows++
                        null
                    }
                if (node != null) {
                    try {
                        check(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "input focus action rejected" }
                        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "input click action rejected" }
                        return
                    } finally {
                        node.recycle()
                    }
                }
            }
            SystemClock.sleep(40)
        }
        error("Autofill input readiness deadline; staleWindows=$staleWindows")
    }

    @Suppress("NestedBlockDepth") // same traversal shape
    private fun findInput(): AccessibilityNodeInfo? {
        // View→accessibility-window-id has no public API; the owned-package + EditText + !password
        // filter in [scanOwned] already scopes to the owned window, so scan every accessibility
        // window and take the first owned input (more robust than pinning one window index).
        val windows = instrumentation.uiAutomation.windows
        try {
            for (window in windows) {
                scanOwned(window.root)?.let { return it }
            }
            return null
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    @Suppress("NestedBlockDepth", "ReturnCount") // each scan path is a distinct terminal (found / not-owned / timeout)
    private fun scanOwned(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        try {
            if (node.className?.toString() == "android.widget.EditText" && !node.isPassword &&
                node.packageName?.toString() == instrumentation.targetContext.packageName
            ) {
                return AccessibilityNodeInfo.obtain(node)
            }
            if (depth < 32) {
                for (index in 0 until minOf(node.childCount, 128)) {
                    scanOwned(node.getChild(index), depth + 1)?.let { return it }
                }
            }
            return null
        } finally {
            node.recycle()
        }
    }
}
