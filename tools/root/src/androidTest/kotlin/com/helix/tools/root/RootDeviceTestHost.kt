package com.helix.tools.root

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before

/** Launch after the runner's activity cleanup, so OEM freezing cannot suspend the test clock. */
open class RootDeviceTestHost {
    private var host: Activity? = null

    @Before
    fun openExecutionHost() {
        // Explicit diagnostic comparison only; normal device regression always uses a visible host.
        if (InstrumentationRegistry.getArguments().getString("helix.root.headless") == "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, RootTestActivity::class.java)
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

class RootTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            TextView(this).apply {
                text = "Helix Root test\nRunning bounded Root lifecycle checks…"
                textSize = 22f
                setPadding(32, 100, 32, 32)
            },
        )
    }
}
