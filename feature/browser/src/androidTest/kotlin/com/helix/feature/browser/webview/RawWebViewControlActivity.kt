package com.helix.feature.browser.webview

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.webkit.WebView
import android.webkit.WebViewClient

/** Test-APK-only control launched by am start, without an instrumentation runner. */
class RawWebViewControlActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var created = 0
    private var requested = 0
    private var autofillOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autofillOnly = intent.getBooleanExtra("autofillOnly", false)
        requested = intent.getIntExtra("iterations", 0)
        require(requested in 1..40_000)
        val startDelayMs = intent.getLongExtra("startDelayMs", 0L)
        require(startDelayMs in 0L..10_000L)
        handler.postDelayed(::createNext, startDelayMs)
    }

    private fun createNext() {
        if (created == 0) Log.i("HelixReferenceTrace", "BEGIN reference control")
        val context = if (intent.getBooleanExtra("activityContext", false)) this else applicationContext
        if (autofillOnly) {
            val manager = requireNotNull(context.getSystemService(AutofillManager::class.java))
            manager.isAutofillSupported
            manager.autofillServiceComponentName
        } else if (intent.getBooleanExtra("hostOnly", false)) {
            val host = WebViewTabHost(context, WebViewResourceLifecycleDeviceTest.NoOpListener)
            if (intent.getBooleanExtra("evaluateHost", false)) host.evaluateFixed("1 + 1") { }
            host.destroy()
        } else {
            val view = WebView(context)
            if (intent.getBooleanExtra("navigateBeforeDestroy", false)) {
                setContentView(view)
                val timeout = Runnable { error("navigation control timed out") }
                view.webViewClient =
                    object : WebViewClient() {
                        private var completed = false

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            if (completed) return
                            completed = true
                            handler.removeCallbacks(timeout)
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.destroy()
                            completedIteration()
                        }
                    }
                handler.postDelayed(timeout, 10_000L)
                view.loadUrl("about:blank")
                return
            }
            view.destroy()
        }
        completedIteration()
    }

    private fun completedIteration() {
        created += 1
        if (created % 100 == 0 || created == requested) {
            Log.i(
                "HelixRawActivity",
                "pid=${Process.myPid()} created=$created requested=$requested autofillOnly=$autofillOnly " +
                    "binderLocal=${Debug.getBinderLocalObjectCount()} " +
                    "binderProxy=${Debug.getBinderProxyObjectCount()} " +
                    "gc=${Debug.getRuntimeStat("art.gc.gc-count")}",
            )
        }
        if (created < requested) {
            handler.postDelayed(::createNext, 5)
        } else {
            Log.i("HelixRawActivity", "PASS created=$created")
            val holdMs = intent.getLongExtra("holdMs", 0L)
            require(holdMs in 0L..30_000L)
            handler.postDelayed({ finish() }, holdMs)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
