package com.helix.app.proot

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.app.ProotNative
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.client.VerifiedRuntimeStore
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-205 slice 3 (developer flavor) — the Runtime-specific readiness journey. Developer-only:
 * the runtime APIs are only on the developer test classpath, so this cannot be a shared test.
 *
 * Two facets, both reusing the HXA-193 verification entry (the supervisor's zero-Job
 * [ProotRuntimeSupervisor.verify] + a real cold bind), never a blind resume:
 *  - actual initialization installs the embedded RootFS and confirms a Verified runtime, with
 *    the readiness view reporting the LINUX goal READY;
 *  - a corrupted (removed) verification anchor is NOT_VERIFIED, and the explicit zero-Job
 *    verify re-establishes it and returns the runtime to READY.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions")
class CapabilityReadinessRuntimeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** The actual embedded-Runtime initialization reuses the HXA-193 verification entry: the
     *  supervisor's zero-Job verify confirms a Verified runtime and the readiness view reports
     *  the LINUX goal READY. */
    @Test
    fun actualRuntimeInitializationReusesNineteenThirteenVerifyEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        installRuntime(context)
        assertTrue(
            "the 193 verification entry must confirm a Verified runtime",
            ProotRuntimeSupervisor(context).verify(System.currentTimeMillis())
                is ProotRuntimeAvailability.Verified,
        )
        assertReadinessRuntimeReady()
    }

    /** A corrupted (removed) verification anchor is NOT_VERIFIED; the explicit zero-Job verify
     *  re-establishes it and returns the runtime to READY — a structured repair, never a resume. */
    @Test
    fun corruptedRuntimeAnchorRecoversToReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        installRuntime(context)
        VerifiedRuntimeStore(context).clear()
        val container = compose.container()
        compose.resetDeterministicUiState()
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        compose.navigateTo("readiness")
        compose.onNodeWithTag("capability-readiness-goal-linux").performClick()
        waitRuntimeItem()
        compose.onNodeWithTag("capability-readiness-action-verify-runtime").assertIsDisplayed()
        compose.onNodeWithTag("capability-readiness-action-verify-runtime").performClick()
        waitForRuntimeReady()
        container.profileStore.switchTo(SafetyProfile.STANDARD)
    }

    // ---------- helpers ----------

    private fun assertReadinessRuntimeReady() {
        val container = compose.container()
        compose.resetDeterministicUiState()
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        compose.navigateTo("readiness")
        compose.onNodeWithTag("capability-readiness-goal-linux").performClick()
        waitRuntimeItem()
        compose.onNodeWithTag("capability-readiness-item-runtime").assertIsDisplayed()
        compose.onNodeWithTag("capability-readiness-action-verify-runtime").assertDoesNotExist()
        container.profileStore.switchTo(SafetyProfile.STANDARD)
    }

    private fun installRuntime(context: Context) {
        val outcome =
            RootFsInstaller.install(
                ProotRuntimeInstaller.buildInstallRequest(
                    context,
                    ProotRuntimeInstaller.loadEmbeddedLock(context),
                    ProotNative.pageSizeBytes(),
                    System.currentTimeMillis(),
                ),
            )
        assertTrue("Actual embedded RootFS must install: $outcome", outcome is InstallOutcome.Success)
    }

    private fun waitRuntimeItem() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose
                .onAllNodesWithTag("capability-readiness-item-runtime")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForRuntimeReady() {
        // The zero-Job verify is a real cold bind (process start + handshake); allow a generous
        // window. Ready = the verify next action is gone (the projection re-reads READY).
        compose.waitUntil(RUNTIME_VERIFY_TIMEOUT_MILLIS) {
            compose
                .onAllNodesWithTag("capability-readiness-action-verify-runtime")
                .fetchSemanticsNodes()
                .isEmpty() &&
                compose
                    .onAllNodesWithTag("capability-readiness-item-runtime")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    private companion object {
        const val RUNTIME_VERIFY_TIMEOUT_MILLIS = 90_000L
    }
}
