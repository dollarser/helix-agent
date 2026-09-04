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
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_FIND
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_FIND_AND_NODE
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_GENERATION_PROBE
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_GLOBAL
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_NODE
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_PAUSE_PROBE
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_PROBE
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_REPLACE_ALLOWLIST
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_RESUME
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_SNAPSHOT
import com.helix.tools.automation.AutomationTestControlReceiver.Companion.ACTION_START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Dedicated-device acceptance for HXA-090 through HXA-093. UiAutomation uses
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
    private var systemGrantDeliberatelyRevoked = false

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
            assertAttackAndRecoveryMatrix(originalComponents)
        } finally {
            if (!systemGrantDeliberatelyRevoked) setEnabledComponents(originalComponents)
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
        val fixturePackage = testContext.packageName
        assertEquals("OK", bridge(ACTION_REPLACE_ALLOWLIST, setOf(fixturePackage)).result)
        launchFixture(AutomationFixtureActivity.MODE_NORMAL)
        val started = bridge(ACTION_START, setOf(fixturePackage))
        assertEquals(AutomationSessionStartStatus.STARTED.name, started.result)
        assertTrue(started.active)

        waitUntil { bridge(ACTION_SNAPSHOT).result == AutomationSnapshotStatus.SUCCESS.name }
        val snapshot = bridge(ACTION_SNAPSHOT)
        assertEquals(fixturePackage, snapshot.snapshotPackage)
        assertTrue(snapshot.snapshotWindow >= 0)
        assertTrue(snapshot.snapshotGeneration > 0)
        assertTrue(snapshot.snapshotNodeCount >= 2)
        assertEquals(32, snapshot.snapshotFirstToken.length)
        assertFalse(snapshot.snapshotTruncated)

        assertNodeAndGlobalActions(fixturePackage)

        launchFixture(AutomationFixtureActivity.MODE_SENSITIVE)
        var latestSensitive = bridge(ACTION_SNAPSHOT)
        waitUntil(failureMessage = { "latest sensitive snapshot=$latestSensitive" }) {
            bridge(ACTION_SNAPSHOT)
                .also { latestSensitive = it }
                .result == AutomationSnapshotStatus.SENSITIVE_UI.name
        }

        launchFixture(AutomationFixtureActivity.MODE_SECURE_CUSTOM)
        var latestSecureCustom = bridge(ACTION_SNAPSHOT)
        waitUntil(failureMessage = { "latest secure/custom snapshot=$latestSecureCustom" }) {
            bridge(ACTION_SNAPSHOT)
                .also { latestSecureCustom = it }
                .result == AutomationSnapshotStatus.UNSUPPORTED_UI.name
        }

        targetContext.sendBroadcast(
            Intent(targetContext, AutomationStopReceiver::class.java).setAction(
                HelixAccessibilityService.ACTION_STOP,
            ),
        )
        waitUntil { !bridge(ACTION_PROBE).active }
        assertFalse(bridge(ACTION_PROBE).active)
        assertEquals(
            AutomationActionStatus.NO_ACTIVE_SESSION.name,
            bridge(
                ACTION_NODE,
                nodeAction = AutomationNodeAction.CLICK,
                token = snapshot.snapshotFirstToken,
            ).result,
        )
    }

    @Suppress("LongMethod")
    private fun assertNodeAndGlobalActions(fixturePackage: String) {
        assertEquals(
            AutomationActionStatus.SUCCEEDED.name,
            performFreshNodeAction(
                action = AutomationNodeAction.CLICK,
                description = "Fixture action",
            ).result,
        )
        waitUntil { bridge(ACTION_FIND, queryText = "clicked").result == AutomationFindStatus.FOUND.name }

        assertEquals(
            AutomationActionStatus.SUCCEEDED.name,
            performFreshNodeAction(
                action = AutomationNodeAction.LONG_CLICK,
                description = "Fixture action",
            ).result,
        )
        waitUntil { bridge(ACTION_FIND, queryText = "long_clicked").result == AutomationFindStatus.FOUND.name }

        assertEquals(
            AutomationActionStatus.SUCCEEDED.name,
            performFreshNodeAction(
                action = AutomationNodeAction.SET_TEXT,
                description = "Text target",
                text = "device text",
            ).result,
        )
        waitUntil { bridge(ACTION_FIND, queryText = "device text").result == AutomationFindStatus.FOUND.name }

        assertEquals(
            AutomationActionStatus.SUCCEEDED.name,
            performFreshNodeAction(
                action = AutomationNodeAction.SCROLL_FORWARD,
                description = "Scroll target",
            ).result,
        )

        var token = findToken(description = "Fixture action")
        val generationBeforeRebuild = bridge(ACTION_GENERATION_PROBE).result.toLong()
        launchFixture(AutomationFixtureActivity.MODE_NORMAL)
        waitUntil { bridge(ACTION_GENERATION_PROBE).result.toLong() > generationBeforeRebuild }
        assertEquals(
            AutomationActionStatus.STALE_TOKEN.name,
            bridge(ACTION_NODE, nodeAction = AutomationNodeAction.CLICK, token = token).result,
        )

        token = findToken(description = "Fixture action")
        Thread.sleep(NodeTokenRegistry.TOKEN_TTL.toMillis() + 250)
        assertEquals(
            AutomationActionStatus.TOKEN_EXPIRED.name,
            bridge(ACTION_NODE, nodeAction = AutomationNodeAction.CLICK, token = token).result,
        )

        targetContext.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitUntil { bridge(ACTION_PAUSE_PROBE).result == AutomationPauseReason.TARGET_CHANGED.name }
        assertEquals(
            AutomationActionStatus.SESSION_PAUSED.name,
            bridge(ACTION_NODE, nodeAction = AutomationNodeAction.CLICK, token = token).result,
        )
        resumeFixture(fixturePackage)

        assertEquals(
            AutomationActionStatus.SUCCEEDED.name,
            bridge(ACTION_GLOBAL, globalAction = AutomationGlobalAction.BACK).result,
        )
        waitUntil { bridge(ACTION_PAUSE_PROBE).result == AutomationPauseReason.TARGET_CHANGED.name }
        resumeFixture(fixturePackage)

        assertEquals(
            AutomationActionStatus.SUCCEEDED.name,
            bridge(ACTION_GLOBAL, globalAction = AutomationGlobalAction.HOME).result,
        )
        waitUntil { bridge(ACTION_PAUSE_PROBE).result == AutomationPauseReason.TARGET_CHANGED.name }
        resumeFixture(fixturePackage)
    }

    private fun resumeFixture(fixturePackage: String) {
        launchFixture(AutomationFixtureActivity.MODE_NORMAL)
        waitUntil { bridge(ACTION_SNAPSHOT).result == AutomationSnapshotStatus.SUCCESS.name }
        var resume = BridgeResult(result = "not attempted", active = true)
        waitUntil(failureMessage = { "latest resume=$resume" }) {
            bridge(ACTION_RESUME, expectedPackage = fixturePackage)
                .also { resume = it }
                .result == AutomationResumeStatus.RESUMED.name
        }
    }

    private fun performFreshNodeAction(
        action: AutomationNodeAction,
        text: String? = null,
        description: String,
    ): BridgeResult {
        var result = BridgeResult(result = AutomationActionStatus.STALE_TOKEN.name, active = true)
        repeat(20) {
            result =
                bridge(
                    ACTION_NODE,
                    nodeAction = action,
                    token = findToken(description = description),
                    text = text,
                )
            if (result.result != AutomationActionStatus.STALE_TOKEN.name) return result
            Thread.sleep(25)
        }
        return result
    }

    private fun findToken(
        text: String? = null,
        description: String? = null,
    ): String {
        var found = bridge(ACTION_FIND, queryText = text, queryDescription = description)
        waitUntil(failureMessage = { "latest find=$found" }) {
            bridge(ACTION_FIND, queryText = text, queryDescription = description)
                .also { found = it }
                .result == AutomationFindStatus.FOUND.name
        }
        return found.actionToken.also { assertEquals(32, it.length) }
    }

    private fun launchFixture(mode: String) {
        targetContext.startActivity(
            Intent(testContext, AutomationFixtureActivity::class.java)
                .putExtra(AutomationFixtureActivity.EXTRA_MODE, mode)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        instrumentation.waitForIdleSync()
        waitUntil { activeWindowPackage() == testContext.packageName }
    }

    @Suppress("DEPRECATION")
    private fun activeWindowPackage(): String? {
        val root = uiAutomation.rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    @Suppress("LongMethod")
    private fun assertAttackAndRecoveryMatrix(originalComponents: Set<String>) {
        assertSystemAndSensitiveTargetsRefused()
        assertTimeAndActionBudgetsCannotBeBypassed()
        assertScreenOffAndServiceDisconnectStopImmediately(originalComponents)
    }

    @Suppress("LongMethod")
    private fun assertSystemAndSensitiveTargetsRefused() {
        val settingsPackage = "com.android.settings"
        targetContext.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        bridge(ACTION_REPLACE_ALLOWLIST, setOf(settingsPackage))
        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            bridge(ACTION_START, setOf(settingsPackage)).result,
        )
        waitUntil {
            bridge(ACTION_SNAPSHOT).result == AutomationSnapshotStatus.SENSITIVE_UI.name
        }
        bridge(AutomationTestControlReceiver.ACTION_STOP)

        for (
        deniedPackage in
        listOf(
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.topjohnwu.magisk",
            "com.example.mobile.banking",
            "com.example.passwordmanager",
            "com.example.authenticator",
        )
        ) {
            assertTrue(deniedPackage, SensitiveAutomationTargetPolicy.isDeniedPackage(deniedPackage))
        }

        val fixturePackage = testContext.packageName
        launchFixture(AutomationFixtureActivity.MODE_ATTACK)
        bridge(ACTION_REPLACE_ALLOWLIST, setOf(fixturePackage))
        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            bridge(ACTION_START, setOf(fixturePackage)).result,
        )
        assertEquals(
            AutomationActionStatus.SENSITIVE_UI.name,
            bridge(
                ACTION_FIND_AND_NODE,
                queryDescription = "Payment confirmation",
                nodeAction = AutomationNodeAction.CLICK,
            ).result,
        )
        assertEquals(
            AutomationActionStatus.SENSITIVE_UI.name,
            bridge(
                ACTION_FIND_AND_NODE,
                queryDescription = "OTP authentication code",
                nodeAction = AutomationNodeAction.SET_TEXT,
                text = "123456",
            ).result,
        )
        assertEquals(
            AutomationActionStatus.SUCCEEDED.name,
            bridge(
                ACTION_FIND_AND_NODE,
                queryDescription = "Non-sensitive false-positive control",
                nodeAction = AutomationNodeAction.CLICK,
            ).result,
        )
        bridge(AutomationTestControlReceiver.ACTION_STOP)
    }

    @Suppress("LongMethod")
    private fun assertTimeAndActionBudgetsCannotBeBypassed() {
        val fixturePackage = testContext.packageName
        launchFixture(AutomationFixtureActivity.MODE_NORMAL)
        bridge(ACTION_REPLACE_ALLOWLIST, setOf(fixturePackage))
        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            bridge(ACTION_START, setOf(fixturePackage), ttlMillis = 250L).result,
        )
        waitUntil { !bridge(ACTION_PROBE).active }
        assertEquals(AutomationStopReason.EXPIRED.name, bridge(ACTION_PROBE).lastStopReason)

        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            bridge(
                ACTION_START,
                setOf(fixturePackage),
                maxActions = AutomationSessionManager.MAX_ACTIONS,
            ).result,
        )
        repeat(AutomationSessionManager.CHECKPOINT_INTERVAL) {
            assertEquals(AutomationActionStatus.TOKEN_UNKNOWN.name, performBudgetAttackAttempt().result)
        }
        assertCheckpointRequiresOneCurrentConfirmation(fixturePackage)

        repeat(AutomationSessionManager.CHECKPOINT_INTERVAL) {
            assertEquals(AutomationActionStatus.TOKEN_UNKNOWN.name, performBudgetAttackAttempt().result)
        }
        assertCheckpointRequiresOneCurrentConfirmation(fixturePackage)

        repeat(AutomationSessionManager.CHECKPOINT_INTERVAL) {
            assertEquals(AutomationActionStatus.TOKEN_UNKNOWN.name, performBudgetAttackAttempt().result)
        }
        val exhausted = bridge(ACTION_PROBE)
        assertFalse(exhausted.active)
        assertEquals(AutomationStopReason.ACTION_BUDGET_EXHAUSTED.name, exhausted.lastStopReason)
        assertEquals(
            AutomationActionStatus.ACTION_BUDGET_EXHAUSTED.name,
            bridge(
                ACTION_NODE,
                nodeAction = AutomationNodeAction.CLICK,
                token = "0".repeat(32),
            ).result,
        )
    }

    private fun assertCheckpointRequiresOneCurrentConfirmation(fixturePackage: String) {
        assertEquals(
            AutomationPauseReason.CHECKPOINT.name,
            bridge(ACTION_PAUSE_PROBE).result,
        )
        assertEquals(
            AutomationActionStatus.CHECKPOINT_REQUIRED.name,
            performBudgetAttackAttempt().result,
        )
        assertEquals(
            AutomationResumeStatus.RESUMED.name,
            bridge(ACTION_RESUME, expectedPackage = fixturePackage).result,
        )
        assertEquals(
            AutomationResumeStatus.NOT_PAUSED.name,
            bridge(ACTION_RESUME, expectedPackage = fixturePackage).result,
        )
    }

    private fun performBudgetAttackAttempt(): BridgeResult =
        bridge(
            ACTION_NODE,
            nodeAction = AutomationNodeAction.CLICK,
            token = "invalid",
        )

    private fun assertScreenOffAndServiceDisconnectStopImmediately(originalComponents: Set<String>) {
        val fixturePackage = testContext.packageName
        launchFixture(AutomationFixtureActivity.MODE_NORMAL)
        bridge(ACTION_REPLACE_ALLOWLIST, setOf(fixturePackage))
        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            bridge(ACTION_START, setOf(fixturePackage)).result,
        )
        shell("input keyevent 223")
        Thread.sleep(250)
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
        waitUntil { !bridge(ACTION_PROBE).active }
        assertEquals(AutomationStopReason.DEVICE_LOCKED.name, bridge(ACTION_PROBE).lastStopReason)

        assertEquals(
            AutomationSessionStartStatus.STARTED.name,
            bridge(ACTION_START, setOf(fixturePackage)).result,
        )
        systemGrantDeliberatelyRevoked = true
        setEnabledComponents(originalComponents)
        var revoked = BridgeResult(result = AutomationServiceState.CONNECTED.name, active = true)
        waitUntil(failureMessage = { "latest revoked state=$revoked" }) {
            bridge(ACTION_PROBE).also { revoked = it }.let { probe ->
                probe.result != AutomationServiceState.CONNECTED.name && !probe.active
            }
        }
        assertEquals(
            AutomationStopReason.SERVICE_DISCONNECTED.name,
            revoked.lastStopReason,
        )
    }

    private fun enableTestService(originalComponents: Set<String>) {
        setEnabledComponents(originalComponents + component.flattenToString())
    }

    private fun setEnabledComponents(components: Set<String>) {
        val enabled = components.joinToString(":")
        shell("settings put secure enabled_accessibility_services $enabled")
        shell("settings put secure accessibility_enabled ${if (components.isEmpty()) 0 else 1}")
    }

    private fun enabledComponents(): Set<String> =
        Settings.Secure
            .getString(
                targetContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            .split(':')
            .filterTo(mutableSetOf()) { it.isNotBlank() }

    @Suppress("LongParameterList", "LongMethod")
    private fun bridge(
        action: String,
        packages: Set<String> = emptySet(),
        queryText: String? = null,
        queryDescription: String? = null,
        nodeAction: AutomationNodeAction? = null,
        globalAction: AutomationGlobalAction? = null,
        token: String? = null,
        text: String? = null,
        expectedPackage: String? = null,
        ttlMillis: Long? = null,
        maxActions: Int? = null,
    ): BridgeResult {
        val nonce = ++nextNonce
        targetContext.sendBroadcast(
            Intent(targetContext, AutomationTestControlReceiver::class.java)
                .setAction(action)
                .putExtra(AutomationTestControlReceiver.EXTRA_NONCE, nonce)
                .putExtra(AutomationTestControlReceiver.EXTRA_PACKAGES, packages.toTypedArray())
                .apply {
                    queryText?.let { putExtra(AutomationTestControlReceiver.EXTRA_QUERY_TEXT, it) }
                    queryDescription?.let {
                        putExtra(AutomationTestControlReceiver.EXTRA_QUERY_DESCRIPTION, it)
                    }
                    nodeAction?.let {
                        putExtra(AutomationTestControlReceiver.EXTRA_NODE_ACTION, it.name)
                    }
                    globalAction?.let {
                        putExtra(AutomationTestControlReceiver.EXTRA_GLOBAL_ACTION, it.name)
                    }
                    token?.let { putExtra(AutomationTestControlReceiver.EXTRA_TOKEN, it) }
                    text?.let { putExtra(AutomationTestControlReceiver.EXTRA_TEXT, it) }
                    expectedPackage?.let {
                        putExtra(AutomationTestControlReceiver.EXTRA_EXPECTED_PACKAGE, it)
                    }
                    ttlMillis?.let {
                        putExtra(AutomationTestControlReceiver.EXTRA_TTL_MILLIS, it)
                    }
                    maxActions?.let {
                        putExtra(AutomationTestControlReceiver.EXTRA_MAX_ACTIONS, it)
                    }
                },
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
            snapshotPackage =
                preferences.getString(AutomationTestControlReceiver.KEY_SNAPSHOT_PACKAGE, null),
            snapshotWindow =
                preferences.getInt(AutomationTestControlReceiver.KEY_SNAPSHOT_WINDOW, -1),
            snapshotGeneration =
                preferences.getLong(AutomationTestControlReceiver.KEY_SNAPSHOT_GENERATION, -1L),
            snapshotNodeCount =
                preferences.getInt(AutomationTestControlReceiver.KEY_SNAPSHOT_NODE_COUNT, 0),
            snapshotFirstToken =
                preferences
                    .getString(AutomationTestControlReceiver.KEY_SNAPSHOT_FIRST_TOKEN, null)
                    .orEmpty(),
            snapshotTruncated =
                preferences.getBoolean(AutomationTestControlReceiver.KEY_SNAPSHOT_TRUNCATED, false),
            snapshotSummary =
                preferences.getString(AutomationTestControlReceiver.KEY_SNAPSHOT_SUMMARY, null).orEmpty(),
            actionToken =
                preferences.getString(AutomationTestControlReceiver.KEY_ACTION_TOKEN, null).orEmpty(),
            actionsAttempted =
                preferences.getInt(AutomationTestControlReceiver.KEY_ACTIONS_ATTEMPTED, -1),
            maxActions = preferences.getInt(AutomationTestControlReceiver.KEY_MAX_ACTIONS, -1),
            lastStopReason =
                preferences.getString(AutomationTestControlReceiver.KEY_LAST_STOP_REASON, null),
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
        failureMessage: () -> String = { "condition was not met" },
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var met = condition()
        while (!met && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            met = condition()
        }
        assertTrue("${failureMessage()} within ${timeoutMillis}ms", met)
    }
}

private data class BridgeResult(
    val result: String,
    val active: Boolean,
    val snapshotPackage: String? = null,
    val snapshotWindow: Int = -1,
    val snapshotGeneration: Long = -1L,
    val snapshotNodeCount: Int = 0,
    val snapshotFirstToken: String = "",
    val snapshotTruncated: Boolean = false,
    val snapshotSummary: String = "",
    val actionToken: String = "",
    val actionsAttempted: Int = -1,
    val maxActions: Int = -1,
    val lastStopReason: String? = null,
)
