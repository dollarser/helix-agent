package com.helix.feature.browser.webview

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.helix.feature.browser.BrowserController

/** Exercises production controller ownership without replacing its WebView callbacks. */
class ControllerReferenceControlActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: BrowserController
    private lateinit var scenario: String
    private var requested = 0
    private var completed = 0
    private var resumeTab: String? = null
    private var networkTab: String? = null
    private val requestTimeout = Runnable { error("HTTP request did not reach control server") }
    private val requestReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.getIntExtra("iteration", -1) != completed) return
                val id = networkTab ?: return
                networkTab = null
                handler.removeCallbacks(requestTimeout)
                networkAction(id)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenario = requireNotNull(intent.getStringExtra("scenario"))
        require(
            scenario in
                setOf(
                    "empty",
                    "denied",
                    "early-close",
                    "stop-close",
                    "settled-close",
                    "clear-history",
                    "network-close",
                    "network-stop",
                    "network-background",
                    "network-recreate",
                ),
        )
        requested = intent.getIntExtra("iterations", 0)
        require(requested in 1..2000)
        controller = BrowserController(this)
        ContextCompat.registerReceiver(
            this,
            requestReceiver,
            IntentFilter("com.helix.feature.browser.test.REQUEST_RECEIVED"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        completed = savedInstanceState?.getInt("completed") ?: 0
        if (completed == requested) {
            finishControl()
        } else {
            handler.postDelayed(::next, if (savedInstanceState == null) 10_000L else 5L)
        }
    }

    private fun next() {
        if (completed == 0) Log.i("HelixReferenceTrace", "BEGIN reference control")
        val id = controller.newTab()
        when (scenario) {
            "empty" -> {
                check(controller.hostView(id) == null)
            }

            "denied" -> {
                controller.navigate(id, "file:///helix-diagnostic-denied")
                check(controller.hostView(id) == null)
            }

            else -> {
                val url =
                    if (scenario.startsWith("network-")) {
                        requireNotNull(intent.getStringExtra("networkUrl")) + "?iteration=$completed"
                    } else {
                        "data:text/html,<title>reference-control</title><p>local</p>"
                    }
                controller.navigate(id, url)
                setContentView(requireNotNull(controller.hostView(id)))
            }
        }
        if (scenario.startsWith("network-")) {
            networkTab = id
            handler.postDelayed(requestTimeout, 10_000L)
        } else if (scenario == "settled-close" || scenario == "clear-history") {
            awaitSettled(id, SystemClock.elapsedRealtime() + 10_000L)
        } else {
            if (scenario == "stop-close") controller.stop(id)
            close(id)
        }
    }

    private fun awaitSettled(
        id: String,
        deadline: Long,
    ) {
        val tab =
            controller.state.value.tabs
                .single { it.id == id }
        check(tab.error == null) { "production navigation failed: ${tab.error}" }
        if (!tab.isLoading && tab.navigationGeneration > 0) {
            close(id)
        } else {
            check(SystemClock.elapsedRealtime() < deadline) { "production navigation timed out" }
            handler.postDelayed({ awaitSettled(id, deadline) }, 5L)
        }
    }

    private fun close(id: String) {
        val oldView = controller.hostView(id)
        if (scenario == "clear-history") controller.clearHistory() else controller.closeTab(id)
        check(controller.hostView(id) == null)
        check(oldView?.parent == null) { "destroyed view remained attached" }
        check(
            controller.state.value.tabs
                .none { it.id == id },
        )
        completed += 1
        if (completed % 100 == 0 || completed == requested) {
            Log.i("HelixControllerTrace", "scenario=$scenario completed=$completed")
        }
        if (completed < requested) {
            handler.postDelayed(::next, 5L)
        } else {
            finishControl()
        }
    }

    private fun networkAction(id: String) {
        when (scenario) {
            "network-background" -> {
                resumeTab = id
                check(moveTaskToBack(true)) { "task did not enter background" }
                Log.i("HelixReferenceTrace", "BACKGROUND iteration=$completed")
            }

            "network-recreate" -> {
                completed += 1
                recreate()
            }

            else -> {
                if (scenario == "network-stop") controller.stop(id)
                close(id)
            }
        }
    }

    override fun onPause() {
        controller.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        controller.resume()
        resumeTab?.let { id ->
            resumeTab = null
            close(id)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("completed", completed)
        super.onSaveInstanceState(outState)
    }

    private fun finishControl() {
        Log.i("HelixControllerTrace", "PASS created=$completed scenario=$scenario")
        handler.postDelayed({ finish() }, 30_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(requestReceiver)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }
}
