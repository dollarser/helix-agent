package com.helix.feature.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/** Real Activity Context for browser tests; no Application Context fallback. */
class BrowserTestActivity : Activity() {
    lateinit var controller: BrowserController
    lateinit var owner: BrowserViewOwner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BrowserController(this)
        owner = BrowserViewOwner(this)
        controller.attach(owner)
    }

    override fun onResume() {
        super.onResume()
        controller.resume(owner)
    }

    override fun onPause() {
        controller.pause(owner)
        super.onPause()
    }

    override fun onDestroy() {
        controller.detach(owner)
        super.onDestroy()
    }
}

internal class BrowserActivityFixture : AutoCloseable {
    val scenario: ActivityScenario<BrowserTestActivity> =
        ActivityScenario.launch(
            Intent(InstrumentationRegistry.getInstrumentation().targetContext, BrowserTestActivity::class.java),
        )
    lateinit var controller: BrowserController
        private set

    init {
        scenario.onActivity { controller = it.controller }
    }

    override fun close() = scenario.close()
}
