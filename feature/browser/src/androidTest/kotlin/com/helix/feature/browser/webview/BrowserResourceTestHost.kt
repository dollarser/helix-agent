package com.helix.feature.browser.webview

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName

/** Launch after the runner's activity cleanup, so OEM freezing cannot suspend the test clock. */
open class BrowserResourceTestHost {
    private var host: Activity? = null

    @get:Rule
    val resourceTestName = TestName()

    @Before
    fun openExecutionHost() {
        // Preserve the separately driven legacy soak/control lifecycle contract.
        if (resourceTestName.methodName in setOf("continuousResourceSoak", "rawPlatformWebViewLifecycleControl")) return
        // Explicit diagnostic comparison only; normal device regression always uses a visible host.
        if (InstrumentationRegistry.getArguments().getString("helix.browser.resources.headless") == "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, BrowserResourceTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
    }

    @After
    fun closeExecutionHost() {
        host?.let { activity ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        }
        host = null
    }
}

class BrowserResourceTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            TextView(this).apply {
                text = "Helix browser resource test\nRunning lifecycle checks…"
                textSize = 22f
                setPadding(32, 100, 32, 32)
            },
        )
    }
}
