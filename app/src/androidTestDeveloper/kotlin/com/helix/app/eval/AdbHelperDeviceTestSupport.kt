package com.helix.app.eval

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.automation.AutomationModule
import com.helix.extensions.mobileuse.automation.AutomationBackendState

/** Host starts the computer helper only after instrumentation has established its actual app process. */
internal fun awaitComputerAdbHelper() {
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        android.os.Bundle().apply {
            putString("stream", "ADB_HELPER_START_NOW\n")
        },
    )
    val deadline = SystemClock.elapsedRealtime() + 30_000
    while (AutomationModule.computerAdbBackend?.state() != AutomationBackendState.READY &&
        SystemClock.elapsedRealtime() < deadline
    ) {
        SystemClock.sleep(50)
    }
    check(AutomationModule.computerAdbBackend?.state() == AutomationBackendState.READY) {
        "Computer helper not connected; no grant or Shizuku fallback is substituted"
    }
}
