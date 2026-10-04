package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.automation.shizuku.PrivilegedUiTransactions
import com.helix.app.automation.shizuku.RootAutomationService
import com.helix.app.automation.shizuku.ShizukuUiSelector
import com.helix.tools.root.LibsuRootAccess
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit owner opt-in. Magisk prompt must be granted through its ordinary UI. */
class RootMobileUseProbeDeviceTest {
    @Test fun applicationGrantedRootObservesAndClicksFixture() {
        val option = InstrumentationRegistry.getArguments().getString("helixRootProbe")
        assumeTrue(option != null)
        require(option == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val access = LibsuRootAccess(app, RootAutomationService::class.java)
        try {
            access.requestRoot()
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (access.connectedBinder() == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            val binder = requireNotNull(access.connectedBinder()) { "${access.status()}" }
            assertEquals(0, PrivilegedUiTransactions.uid(binder))
            val target = "${app.packageName}.test"
            app.startActivity(
                Intent()
                    .setComponent(ComponentName(target, AutomationEvaluationActivity::class.java.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("recordClicks", true),
            )
            SystemClock.sleep(1500)
            val reply =
                PrivilegedUiTransactions.click(
                    binder,
                    ShizukuUiSelector(target, "android:id/button1", "FIXTURE CLICK"),
                    { _, _, _ -> access.connectedBinder() === binder },
                    { access.connectedBinder() === binder },
                )
            println("ROOT_PROBE_UID=0;result=$reply")
            assertEquals("DISPATCHED", reply["status"]?.jsonPrimitive?.content)
        } finally {
            access.disconnect()
        }
    }
}
