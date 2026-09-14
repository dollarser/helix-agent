package com.helix.runtime.quickjs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before

/** Launch after the runner's activity cleanup, so OEM freezing cannot suspend the test clock. */
open class QuickJsDeviceTestHost {
    private var host: Activity? = null

    @Before
    fun openExecutionHost() {
        // Explicit diagnostic comparison only; normal device regression always uses a visible host.
        if (InstrumentationRegistry.getArguments().getString("helix.quickjs.headless") == "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, QuickJsTestActivity::class.java)
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

class QuickJsTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            TextView(this).apply {
                text = "Helix QuickJS test\nRunning isolated execution checks…"
                textSize = 22f
                setPadding(32, 100, 32, 32)
            },
        )
    }
}
