package com.helix.app.test

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import org.junit.After
import org.junit.Before

/** Foreground fixture for asynchronous tests; does not change device background restrictions. */
open class ForegroundDeviceTestHost {
    private var host: Activity? = null

    @Before
    fun openForegroundHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
    }

    @After
    fun closeForegroundHost() {
        host?.let { activity ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        }
        host = null
    }
}
