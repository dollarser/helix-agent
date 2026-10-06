package com.helix.app.test

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import org.junit.After
import org.junit.Before

/** Foreground fixture for asynchronous tests; does not change device background restrictions. */
open class ForegroundDeviceTestHost {
    private var host: ActivityScenario<MainActivity>? = null

    @Before
    fun openForegroundHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        host =
            ActivityScenario.launch(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
    }

    @After
    fun closeForegroundHost() {
        // close waits for DESTROYED. An asynchronous finish races the next test's launch into
        // singleTask MainActivity and can consume its intent without creating a new Activity.
        host?.close()
        host = null
    }
}
