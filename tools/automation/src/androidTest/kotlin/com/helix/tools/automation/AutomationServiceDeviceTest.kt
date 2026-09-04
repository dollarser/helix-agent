package com.helix.tools.automation

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_DISABLE_SERVICE
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_PROBE
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_REPLACE_ALLOWLIST
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Dedicated-device acceptance for HXA-090. UiAutomation uses
 * [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES], otherwise instrumentation itself
 * prevents the real service from binding. Cleanup uses the service's user-revocation path
 * (`disableSelf`) and preserves other enabled services.
 */
@RunWith(AndroidJUnit4::class)
class AutomationServiceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val targetContext: Context = instrumentation.targetContext.applicationContext
    private val testContext: Context = instrumentation.context.applicationContext
    private val component = ComponentName(targetContext.packageName, HelixAccessibilityService::class.java.name)
    private var nextNonce = 0L

    @Test
    fun serviceAllowlistSessionAndImmediateStopWorkEndToEnd() {
        val originalComponents = enabledComponents().minus(component.flattenToString())
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm grant ${targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        }
        enableTestService(originalComponents)

        try {
            waitUntil { bridge(ACTION_PROBE).result == AutomationServiceState.CONNECTED.name }
            bridge(AutomationTestControlReceiver.ACTION_STOP)
            bridge(ACTION_REPLACE_ALLOWLIST, emptySet())

            assertManifestContract()
            assertPermissionCenterContract()
            assertNonAllowlistedTargetRefused()
            assertFixtureSessionAndImmediateStop()
            assertServiceDisableStopsLiveSession(originalComponents)
        } finally {
            if (bridge(ACTION_PROBE).result == AutomationServiceState.CONNECTED.name) {
                bridge(ACTION_DISABLE_SERVICE)
            }
        }
    }

    private fun assertManifestContract() {
        val serviceInfo =
            targetContext.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", serviceInfo.permission)
        assertTrue(serviceInfo.exported)
        assertTrue(serviceInfo.metaData?.getInt("android.accessibilityservice", 0) != 0)
    }

    private fun assertPermissionCenterContract() {
        val center = AutomationPermissionCenter(targetContext)
        assertEquals(AutomationServiceState.CONNECTED.name, bridge(ACTION_PROBE).result)
        assertEquals(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            center.accessibilitySettingsIntent().action,
        )
        assertFalse(bridge(ACTION_PROBE).active)
    }

    private fun assertNonAllowlistedTargetRefused() {
        bridge(ACTION_REPLACE_ALLOWLIST, setOf(testContext.packageName))
        val result = bridge(ACTION_START, setOf("com.example.not.allowed"))
        assertEquals(AutomationSessionStartStatus.TARGET_NOT_ALLOWLISTED.name, result.result)
        assertFalse(result.active)
    }

    private fun assertFixtureSessionAndImmediateStop() {
        instrumentation.startActivitySync(
            Intent(testContext, AutomationFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val fixturePackage = testContext.packageName
        assertEquals("OK", bridge(ACTION_REPLACE_ALLOWLIST, setOf(fixturePackage)).result)
        val started = bridge(ACTION_START, setOf(fixturePackage))
        assertEquals(AutomationSessionStartStatus.STARTED.name, started.result)
        assertTrue(started.active)

        targetContext.sendBroadcast(
            Intent(targetContext, AutomationStopReceiver::class.java).setAction(
                HelixAccessibilityService.ACTION_STOP,
            ),
        )
        waitUntil { !bridge(ACTION_PROBE).active }
        assertFalse(bridge(ACTION_PROBE).active)
    }

    private fun assertServiceDisableStopsLiveSession(originalComponents: Set<String>) {
        bridge(ACTION_REPLACE_ALLOWLIST, setOf(testContext.packageName))
        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            bridge(ACTION_START, setOf(testContext.packageName)).result,
        )

        assertEquals("OK", bridge(ACTION_DISABLE_SERVICE).result)
        waitUntil {
            enabledComponents() == originalComponents &&
                bridge(ACTION_PROBE).result != AutomationServiceState.CONNECTED.name
        }
        assertEquals(originalComponents, enabledComponents())
        assertFalse(bridge(ACTION_PROBE).active)
    }

    private fun enableTestService(originalComponents: Set<String>) {
        val enabled = (originalComponents + component.flattenToString()).joinToString(":")
        shell("settings put secure enabled_accessibility_services $enabled")
        shell("settings put secure accessibility_enabled 1")
    }

    private fun enabledComponents(): Set<String> =
        Settings.Secure
            .getString(
                targetContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            .split(':')
            .filterTo(mutableSetOf()) { it.isNotBlank() }

    private fun bridge(
        action: String,
        packages: Set<String> = emptySet(),
    ): BridgeResult {
        val nonce = ++nextNonce
        targetContext.sendBroadcast(
            Intent(targetContext, AutomationTestControlReceiver::class.java)
                .setAction(action)
                .putExtra(AutomationTestControlReceiver.EXTRA_NONCE, nonce)
                .putExtra(AutomationTestControlReceiver.EXTRA_PACKAGES, packages.toTypedArray()),
        )
        val preferences =
            targetContext.getSharedPreferences(
                AutomationTestControlReceiver.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
        waitUntil { preferences.getLong(AutomationTestControlReceiver.KEY_NONCE, -1) == nonce }
        return BridgeResult(
            result = preferences.getString(AutomationTestControlReceiver.KEY_RESULT, null).orEmpty(),
            active = preferences.getBoolean(AutomationTestControlReceiver.KEY_ACTIVE, false),
        )
    }

    private fun shell(command: String): String {
        val descriptor = uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }.also {
            descriptor.close()
        }
    }

    private fun waitUntil(
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("condition was not met within ${timeoutMillis}ms", condition())
    }
}

private data class BridgeResult(
    val result: String,
    val active: Boolean,
)
