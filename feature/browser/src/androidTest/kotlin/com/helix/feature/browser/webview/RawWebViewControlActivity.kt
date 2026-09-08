package com.helix.feature.browser.webview

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.autofill.AutofillManager
import android.webkit.WebView

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
        handler.post(::createNext)
    }

    private fun createNext() {
        val context = if (intent.getBooleanExtra("activityContext", false)) this else applicationContext
        if (autofillOnly) {
            val manager = requireNotNull(context.getSystemService(AutofillManager::class.java))
            manager.isAutofillSupported
            manager.autofillServiceComponentName
        } else {
            WebView(context).destroy()
        }
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
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
