package com.helix.app.root

import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.policy.GrantState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HXA-094 real-app lifecycle evidence on a rooted physical device (granted mode only).
 *
 * The instrumentation hosts the app in its own process, so "the real App" is this process and
 * its RootModule is the production wiring. On this device the app SELinux domain cannot read
 * /proc entries of the su domain (verified: stat and the whole entry are denied), so the
 * OS-level shell evidence is collected by the owned run script from the host's own root, which
 * polls the system process list while this test runs and correlates it with the phase file
 * this test writes after each lifecycle step. See
 * scripts/debug/2026-09-16/run-hxa094-095-rooted.sh.
 *
 * `backgroundTransitionFailsClosedAndLeavesNoRootProcess` proves the real App
 * background/foreground transition: onStop fails the live grant closed, the grant never returns
 * to GRANTED, and returning to the foreground does not re-request.
 * `processRecreationStartsCleanWithoutStaleAuthority` uses the two-run cross-process marker
 * protocol (setup kills this process, verify runs in the fresh process) to prove process
 * recreation starts without stale authority. The two tests are phase-exclusive (the
 * recreation test only runs with an explicit setup/verify phase argument, and the background
 * test only without one), so no method ordering is required.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // phase-specific acceptance and shared UI/identity helpers
class RootLifecycleDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        // Production seam: closes any session and disconnects the underlying access.
        RootModule.closeSession()
        phaseFile().delete()
    }

    @Test
    fun backgroundTransitionFailsClosedAndLeavesNoRootProcess() {
        assumeGrantedMode()
        val phase = InstrumentationRegistry.getArguments().getString(RECREATION_PHASE_ARGUMENT)
        assumeTrue("process recreation runs are phase-scoped", phase == null)
        assertEquals(GrantState.UNAVAILABLE, RootModule.capabilityState())
        requestRootThroughTheUi()
        writePhase("connected")
        Thread.sleep(SETTLE_MS)

        val returnIntent =
            Intent(composeRule.activity.intent).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        // The real App leaves the foreground: MainActivity.onStop -> RootModule.onAppBackgrounded.
        composeRule.activity.runOnUiThread { composeRule.activity.moveTaskToBack(true) }
        waitUntil(GRANT_TIMEOUT_MS, {
            RootModule.capabilityState() == GrantState.DENIED
        }, { RootModule.capabilityState().name })
        // Shell close is async (closeCachedShellAsync); settle before the host timeline marks
        // the transition complete so a dying su is not mistaken for a survivor.
        Thread.sleep(SHELL_CLOSE_SETTLE_MS)
        writePhase("backgrounded")

        // Return to the foreground: the same activity instance resumes, the grant stays LOST,
        // and no automatic re-request reopens a shell or grant.
        context.startActivity(returnIntent)
        composeRule.waitForIdle()
        waitUntil(GRANT_TIMEOUT_MS, { rootStateText().contains("LOST") }, { rootStateText() })
        Thread.sleep(SETTLE_MS)
        writePhase("foreground")
        Thread.sleep(SETTLE_MS)
        assertEquals(
            "the grant must stay failed-closed after the foreground return",
            GrantState.DENIED,
            RootModule.capabilityState(),
        )
        assertNotEquals(
            "no automatic rebind after the foreground return",
            GrantState.GRANTED,
            RootModule.capabilityState(),
        )
    }

    @Test
    fun realAppDispatcherHonorsRootScopeAndToolDisable() {
        assumeGrantedMode()
        requestRootThroughTheUi()
        composeRule.onNodeWithTag("root-scope-system-etc").performClick()
        composeRule.onNodeWithTag("root-session-start").performClick()
        val probe = RootDispatchProbe(composeRule.container())
        probe.verifyFiveToolsAndDisable()
        composeRule.onNodeWithTag("root-stop").performClick()
        probe.verifyDisconnectedScopeCannotRead()
        assertNotEquals(GrantState.GRANTED, RootModule.capabilityState())
    }

    @Test
    fun processRecreationStartsCleanWithoutStaleAuthority() {
        when (InstrumentationRegistry.getArguments().getString(RECREATION_PHASE_ARGUMENT)) {
            "setup" -> {
                // Grants through the UI, records the live app pid, then dies like a system
                // process death. The host script records the shell pid/process group itself.
                assumeGrantedMode()
                requestRootThroughTheUi()
                writePhase("connected")
                Thread.sleep(SETTLE_MS)
                markerFile().writeText(Process.myPid().toString())
                // Hard process death (not onStop): the next verify run starts in a fresh process.
                Process.killProcess(Process.myPid())
                error("process kill must terminate this run")
            }

            "verify" -> {
                // Fresh process: the stale authority must be gone and nothing may rebind
                // automatically. The old shell's OS-level disappearance (process + process
                // group, system-wide, including reparented orphans) is checked by the run
                // script from the host's root before this run starts.
                assumeGrantedMode()
                val marker = markerFile()
                try {
                    require(marker.exists()) { "HXA-094 setup phase must run before verify" }
                    val oldAppPid = marker.readText().trim().toInt()
                    assertNotEquals("expected a fresh app process", oldAppPid, Process.myPid())

                    // The recreated process holds no Root authority.
                    assertEquals(GrantState.UNAVAILABLE, RootModule.capabilityState())

                    writePhase("verify")
                    // No automatic rebind after startup: the authority-less state holds.
                    Thread.sleep(SETTLE_MS)
                    assertEquals(
                        "no automatic rebind after process recreation",
                        GrantState.UNAVAILABLE,
                        RootModule.capabilityState(),
                    )
                } finally {
                    marker.delete()
                }
            }

            else -> {
                assumeTrue("process recreation uses the dedicated setup/verify runs", false)
            }
        }
    }

    private fun requestRootThroughTheUi() {
        composeRule.resetDeterministicUiState()
        composeRule.navigateTo("settings")
        composeRule.onNodeWithText("当前：Standard（默认）").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-advanced-switch").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-risk-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-risk-confirm").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-root-section").assertIsDisplayed()
        // The owner can pre-approve the package or approve a manager prompt. Retry within
        // a bounded deadline; a pending su process alone is not evidence of a visible prompt.
        composeRule.onNodeWithTag("root-request").performClick()
        val grantDeadline = SystemClock.elapsedRealtime() + GRANT_TIMEOUT_MS
        var lastRequestAt = SystemClock.elapsedRealtime()
        var state = ""
        while (SystemClock.elapsedRealtime() < grantDeadline) {
            state = rootStateText()
            if (state.contains("GRANTED") && state.contains("CONNECTED")) break
            if (SystemClock.elapsedRealtime() - lastRequestAt >= PROMPT_CYCLE_MS) {
                composeRule.onNodeWithTag("root-request").performClick()
                composeRule.waitForIdle()
                lastRequestAt = SystemClock.elapsedRealtime()
            }
            composeRule.mainClock.advanceTimeBy(UI_POLL_ADVANCE_MS)
            composeRule.waitForIdle()
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(
            "grant not reached within ${GRANT_TIMEOUT_MS}ms; check the Root manager policy for " +
                "com.helix.agent.developer; last observed: $state",
            state.contains("GRANTED") && state.contains("CONNECTED"),
        )
        assertEquals(GrantState.GRANTED, RootModule.capabilityState())
    }

    private fun rootStateText(): String =
        runCatching {
            composeRule
                .onNodeWithTag("root-state")
                .fetchSemanticsNode()
                .config
                .get(SemanticsProperties.Text)
                .joinToString("") { it.text }
        }.getOrDefault("")

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
        describe: () -> String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            composeRule.mainClock.advanceTimeBy(UI_POLL_ADVANCE_MS)
            composeRule.waitForIdle()
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertEquals("state not reached within ${timeoutMs}ms; last observed: ${describe()}", true, condition())
    }

    private fun assumeGrantedMode() {
        assumeTrue(
            "HXA-094 lifecycle evidence requires the dedicated granted rooted-device run",
            InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) == "granted",
        )
    }

    private fun writePhase(name: String) {
        val accessField = RootModule::class.java.getDeclaredField("access").apply { isAccessible = true }
        val access = requireNotNull(accessField.get(null))
        val method = access.javaClass.declaredMethods.single { it.name.startsWith("rootServiceProcessIdForTest") }
        method.isAccessible = true
        val remotePid = method.invoke(access) as Int?
        if (name == "connected") require(remotePid != null && remotePid > 0)
        phaseFile().writeText("$name ${remotePid ?: 0}")
    }

    private fun phaseFile() = context.filesDir.resolve(PHASE_FILE)

    private fun markerFile() = context.filesDir.resolve(MARKER_FILE)

    private companion object {
        const val EXPECTED_ROOT_ARGUMENT = "hxa094ExpectedRoot"
        const val RECREATION_PHASE_ARGUMENT = "hxa094RecreationPhase"
        const val PHASE_FILE = "hxa094-phase.txt"
        const val MARKER_FILE = "hxa094-recreation.txt"
        const val GRANT_TIMEOUT_MS = 180_000L
        const val PROMPT_CYCLE_MS = 35_000L
        const val SETTLE_MS = 2_500L
        const val SHELL_CLOSE_SETTLE_MS = 1_000L
        const val POLL_INTERVAL_MS = 100L
        const val UI_POLL_ADVANCE_MS = 300L
    }
}
