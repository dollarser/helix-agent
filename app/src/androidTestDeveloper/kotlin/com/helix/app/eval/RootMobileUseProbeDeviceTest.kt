package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.backend.PrivilegedUiTransactions
import com.helix.extensions.mobileuse.automation.backend.ShizukuUiSelector
import com.helix.tools.deviceaccess.DeviceAccess
import com.helix.tools.root.HelixRootService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit owner opt-in. Root-manager prompts must be granted through their ordinary UI. */
class RootMobileUseProbeDeviceTest {
    @Test fun applicationGrantedRootObservesAndClicksFixture() = probe("DISPATCHED")

    @Test fun authorizedRootWithBlockedServicesRefusesWithoutClicking() {
        val option = InstrumentationRegistry.getArguments().getString("helixRootServiceRefusal")
        assumeTrue(option != null)
        require(option == "true")
        probe("PRIVILEGED_SERVICE_UNAVAILABLE")
    }

    private fun probe(expectedStatus: String): Unit =
        runBlocking {
            val option = InstrumentationRegistry.getArguments().getString("helixRootProbe")
            assumeTrue(option != null)
            require(option == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val host = "root-authorization-probe"
            val pluginService = app.appContainer.pluginService
            val plugin = pluginService.list().single { it.native?.pluginId == "mobile-use" }
            DeviceAccess.configure(app, host, HelixRootService::class.java)
            val access = requireNotNull(DeviceAccess.root("mobile-use"))
            try {
                pluginService.setEnabled(plugin.id, false)
                access.disconnect()
                DeviceAccess.requestRootFromUser(host)
                val authorizationDeadline = SystemClock.elapsedRealtime() + 60_000
                while (DeviceAccess.root(host)?.cachedAppGrant != true &&
                    SystemClock.elapsedRealtime() < authorizationDeadline
                ) {
                    SystemClock.sleep(100)
                }
                DeviceAccess.connectEnabledRootConsumers()
                assertEquals(null, access.connectedBinder())
                pluginService.setEnabled(plugin.id, true)
                DeviceAccess.connectEnabledRootConsumers()
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (access.connectedBinder() == null && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100)
                }
                val binder = requireNotNull(access.connectedBinder()) { "${access.status()}" }
                assertEquals(0, PrivilegedUiTransactions.uid(binder))
                verifyFixture(app, access, binder, expectedStatus)
            } finally {
                pluginService.setEnabled(plugin.id, plugin.enabled)
                access.disconnect()
                DeviceAccess.disconnectRoot(host)
            }
        }

    private fun verifyFixture(
        app: HelixApplication,
        access: com.helix.tools.root.LibsuRootAccess,
        binder: android.os.IBinder,
        expectedStatus: String,
    ) {
        val target = "${app.packageName}.test"
        app.startActivity(
            Intent()
                .setComponent(ComponentName(target, AutomationEvaluationActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("recordClicks", true),
        )
        SystemClock.sleep(1500)
        val reply =
            PrivilegedUiTransactions.click(
                binder,
                ShizukuUiSelector(target, "android:id/button1", "Fixture click"),
                { _, _, _ -> access.connectedBinder() === binder },
                { access.connectedBinder() === binder },
            )
        println("ROOT_PROBE_UID=0;result=$reply")
        assertEquals(expectedStatus, reply["status"]?.jsonPrimitive?.content)
        app.contentResolver
            .query(
                android.net.Uri.parse("content://$target.capability-state/click"),
                null,
                null,
                null,
                null,
            )!!
            .use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(
                    if (expectedStatus ==
                        "DISPATCHED"
                    ) {
                        1
                    } else {
                        0
                    },
                    cursor.getInt(cursor.getColumnIndexOrThrow("clicks")),
                )
            }
    }
}
