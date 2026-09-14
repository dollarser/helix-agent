package com.helix.feature.browser

import android.os.Process
import android.os.SystemClock
import android.webkit.WebView
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.instrumentation
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.onMain
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Failure-only structural evidence: never serializes field text, passwords, URLs or page content. */
internal object AutofillFailureEvidence {
    fun capture(
        file: File,
        runId: String,
        cycle: Int,
        stage: String,
        lifecycle: String,
        view: WebView?,
        failure: Throwable,
    ) {
        val data =
            JSONObject()
                .put("runId", runId)
                .put("cycle", cycle)
                .put("stage", stage)
                .put("pid", Process.myPid())
                .put("monotonicMs", SystemClock.elapsedRealtime())
                .put("failureType", failure.javaClass.simpleName)
                .put("lifecycle", lifecycle)
        if (view != null) {
            data.put(
                "view",
                onMain {
                    JSONObject()
                        .put("identity", System.identityHashCode(view))
                        .put("attached", view.isAttachedToWindow)
                        .put("windowFocus", view.hasWindowFocus())
                        .put("viewFocus", view.hasFocus())
                        // View→accessibility-window-id has no public API; a missing host-window token
                        // is the structural signal that a view cannot expose an a11y node at failure time.
                        .put("hasWindowToken", view.windowToken != null)
                        .put("visibility", view.visibility)
                        .put("width", view.width)
                        .put("height", view.height)
                },
            )
        }
        val windows = instrumentation.uiAutomation.windows
        try {
            val rows = JSONArray()
            windows.take(32).forEach { window ->
                rows.put(
                    JSONObject()
                        .put("id", window.id)
                        .put("type", window.type)
                        .put("active", window.isActive)
                        .put("focused", window.isFocused)
                        .put("accessibilityFocused", window.isAccessibilityFocused),
                )
            }
            data.put("windows", rows)
        } finally {
            windows.forEach { it.recycle() }
        }
        val provider = WebView.getCurrentWebViewPackage()
        data
            .put("webViewProvider", provider?.packageName ?: JSONObject.NULL)
            .put("webViewVersion", provider?.versionName ?: JSONObject.NULL)
        file.writeText(data.toString() + "\n")
    }
}
