package com.helix.app.eval

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.extensions.mobileuse.automation.backend.PrivilegedUiTransactions
import com.helix.tools.deviceaccess.DeviceAccess
import com.helix.tools.root.RootGrantState

/** Explicit instrumentation opt-in; a manager prompt still requires the owner, never an automatic click. */
internal fun awaitOwnerGrantedRootBackend() {
    if (InstrumentationRegistry.getArguments().getString("helixRootHelper") != "true") return
    check(InstrumentationRegistry.getArguments().getString("helixAdbHelper") != "true")
    val access = requireNotNull(DeviceAccess.root("mobile-use"))
    DeviceAccess.requestRootFromUser("mobile-use")
    val deadline = SystemClock.elapsedRealtime() + 60_000
    while (access.status().grant == RootGrantState.REQUESTING && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(100)
    }
    check(access.status().grant == RootGrantState.GRANTED) { "Root grant not ready: ${access.status()}" }
    DeviceAccess.connectRoot("mobile-use")
    while (access.connectedBinder() == null && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(100)
    }
    val binder = requireNotNull(access.connectedBinder()) { "Root connection not ready: ${access.status()}" }
    check(PrivilegedUiTransactions.uid(binder) == 0)
    InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        Bundle().apply {
            putString("stream", "ROOT_BACKEND_READY uid=0\n")
        },
    )
}

internal fun disconnectOwnerRootBackend() {
    if (InstrumentationRegistry.getArguments().getString("helixRootHelper") == "true") {
        DeviceAccess.disconnectRoot("mobile-use")
    }
}
