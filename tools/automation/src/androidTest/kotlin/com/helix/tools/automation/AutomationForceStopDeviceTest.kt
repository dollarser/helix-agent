package com.helix.tools.automation

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class AutomationForceStopSetupDeviceTest {
    @Test
    fun createsLiveSessionForHostForceStop() {
        assumeTrue(forceStopPhase() == PHASE_SETUP)
        val harness = ForceStopHarness()
        harness.saveAndEnableService()
        harness.waitUntil {
            harness.bridge(AutomationTestControlReceiver.ACTION_PROBE).result ==
                AutomationServiceState.CONNECTED.name
        }
        harness.launchFixture()
        val packageName = harness.targetContext.packageName
        assertEquals(
            "OK",
            harness.bridge(AutomationTestControlReceiver.ACTION_REPLACE_ALLOWLIST, setOf(packageName)).result,
        )
        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            harness.bridge(AutomationTestControlReceiver.ACTION_START, setOf(packageName)).result,
        )
        val snapshot = harness.bridge(AutomationTestControlReceiver.ACTION_SNAPSHOT)
        assertEquals(AutomationSnapshotStatus.SUCCESS.name, snapshot.result)
        assertTrue(snapshot.active)
        assertEquals(32, snapshot.token.length)
        harness.waitUntil {
            harness.activeNotificationDump().contains(ForceStopHarness.NOTIFICATION_KEY_FRAGMENT)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class AutomationForceStopRecoveryDeviceTest {
    @Test
    fun processRestartCannotRestoreSessionTokenOrNotification() {
        assumeTrue(forceStopPhase() == PHASE_RECOVERY)
        val harness = ForceStopHarness()
        val oldToken = harness.savedToken()
        assertEquals(32, oldToken.length)
        val probe = harness.bridge(AutomationTestControlReceiver.ACTION_PROBE)
        assertFalse(probe.active)
        val oldTokenResult =
            harness
                .bridge(
                    AutomationTestControlReceiver.ACTION_NODE,
                    token = oldToken,
                    nodeAction = AutomationNodeAction.CLICK,
                ).result
        assertTrue(
            oldTokenResult,
            oldTokenResult in
                setOf(
                    AutomationActionStatus.NO_ACTIVE_SESSION.name,
                    AutomationActionStatus.SERVICE_NOT_CONNECTED.name,
                ),
        )
        assertFalse(harness.activeNotificationDump().contains(ForceStopHarness.NOTIFICATION_KEY_FRAGMENT))
        harness.restoreServices()
    }
}

private fun forceStopPhase(): String? = InstrumentationRegistry.getArguments().getString(FORCE_STOP_PHASE_ARGUMENT)

private const val FORCE_STOP_PHASE_ARGUMENT = "hxa093ForceStopPhase"
private const val PHASE_SETUP = "setup"
private const val PHASE_RECOVERY = "recovery"

private data class ForceStopBridgeResult(
    val result: String,
    val active: Boolean,
    val token: String,
)

private class ForceStopHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val targetContext: Context = instrumentation.targetContext.applicationContext
    private val component =
        ComponentName(targetContext.packageName, HelixAccessibilityService::class.java.name)
    private var nonce = 10_000L

    fun saveAndEnableService() {
        val original = enabledComponents().minus(component.flattenToString())
        check(
            targetContext
                .getSharedPreferences(HARNESS_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ORIGINAL_SERVICES, original.joinToString(":"))
                .commit(),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm grant ${targetContext.packageName} android.permission.POST_NOTIFICATIONS")
        }
        setEnabledComponents(original + component.flattenToString())
    }

    fun restoreServices() {
        val original =
            targetContext
                .getSharedPreferences(HARNESS_PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_ORIGINAL_SERVICES, "")
                .orEmpty()
                .split(':')
                .filterTo(mutableSetOf()) { it.isNotBlank() }
        setEnabledComponents(original)
    }

    fun launchFixture() {
        targetContext.startActivity(
            Intent(targetContext, AutomationFixtureActivity::class.java)
                .putExtra(AutomationFixtureActivity.EXTRA_MODE, AutomationFixtureActivity.MODE_NORMAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
    }

    fun savedToken(): String =
        targetContext
            .getSharedPreferences(
                AutomationTestControlReceiver.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).getString(AutomationTestControlReceiver.KEY_SNAPSHOT_FIRST_TOKEN, null)
            .orEmpty()

    fun bridge(
        action: String,
        packages: Set<String> = emptySet(),
        token: String? = null,
        nodeAction: AutomationNodeAction? = null,
    ): ForceStopBridgeResult {
        nonce += 1
        targetContext.sendBroadcast(
            Intent(targetContext, AutomationTestControlReceiver::class.java)
                .setAction(action)
                .putExtra(AutomationTestControlReceiver.EXTRA_NONCE, nonce)
                .putExtra(AutomationTestControlReceiver.EXTRA_PACKAGES, packages.toTypedArray())
                .apply {
                    token?.let { putExtra(AutomationTestControlReceiver.EXTRA_TOKEN, it) }
                    nodeAction?.let {
                        putExtra(AutomationTestControlReceiver.EXTRA_NODE_ACTION, it.name)
                    }
                },
        )
        val preferences =
            targetContext.getSharedPreferences(
                AutomationTestControlReceiver.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
        waitUntil { preferences.getLong(AutomationTestControlReceiver.KEY_NONCE, -1L) == nonce }
        return ForceStopBridgeResult(
            result =
                preferences.getString(AutomationTestControlReceiver.KEY_RESULT, null).orEmpty(),
            active = preferences.getBoolean(AutomationTestControlReceiver.KEY_ACTIVE, false),
            token =
                preferences
                    .getString(AutomationTestControlReceiver.KEY_SNAPSHOT_FIRST_TOKEN, null)
                    .orEmpty(),
        )
    }

    fun activeNotificationDump(): String = shell("dumpsys notification --noredact").substringBefore("  mArchive=")

    fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        var met = condition()
        while (!met && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            met = condition()
        }
        assertTrue("condition was not met within 20000ms", met)
    }

    private fun enabledComponents(): Set<String> =
        Settings.Secure
            .getString(
                targetContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            .split(':')
            .filterTo(mutableSetOf()) { it.isNotBlank() }

    private fun setEnabledComponents(components: Set<String>) {
        shell("settings put secure enabled_accessibility_services ${components.joinToString(":")}")
        shell("settings put secure accessibility_enabled ${if (components.isEmpty()) 0 else 1}")
    }

    private fun shell(command: String): String {
        val descriptor = uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }.also {
            descriptor.close()
        }
    }

    companion object {
        const val NOTIFICATION_KEY_FRAGMENT = "|com.helix.tools.automation.test|4900|"
        private const val HARNESS_PREFERENCES = "automation_force_stop_harness"
        private const val KEY_ORIGINAL_SERVICES = "original_services"
    }
}
