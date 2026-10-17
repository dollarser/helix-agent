package com.helix.app.proot

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.app.ProotNative
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed files drive readiness; entering settings or returning never repairs them. */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions")
class CapabilityReadinessRuntimeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Actual installation and an explicit diagnostic handshake agree with the readiness projection. */
    @Test
    fun actualRuntimeInitializationMakesTheEnvironmentReady() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        installRuntime(context)
        assertTrue(
            "the installed environment must pass the diagnostic handshake",
            ProotRuntimeSupervisor(context).verify(System.currentTimeMillis())
                is ProotRuntimeAvailability.Verified,
        )
        assertReadinessRuntimeReady()
    }

    @Test fun damagedInstallationOffersMaintenanceWithoutStartingRepair() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        installRuntime(context)
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        val active = requireNotNull(RootFsInstaller.currentActive(root))
        val manifest = java.io.File(root, "${active.installId}/manifest.json")
        val original = manifest.readText()
        val store = compose.container().profileStore
        try {
            manifest.writeText("damaged")
            compose.resetDeterministicUiState()
            store.switchTo(SafetyProfile.ADVANCED)
            compose.navigateTo("setup/readiness")
            compose.onNodeWithTag("capability-readiness-goal-linux").performClick()
            waitRuntimeItem()
            compose.onNodeWithTag("capability-readiness-action-manage-runtime").performClick()
            compose.onNodeWithTag("screen-setup-runtime").assertIsDisplayed()
            compose.onNodeWithTag("settings-proot-manage").assertIsDisplayed()
            org.junit.Assert.assertEquals("damaged", manifest.readText())
        } finally {
            manifest.writeText(original)
            store.switchTo(SafetyProfile.STANDARD)
        }
    }

    @Test fun returningToRuntimeRefreshesActualInstallationStatus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        installRuntime(context)
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        val active = requireNotNull(RootFsInstaller.currentActive(root))
        val manifest = java.io.File(root, "${active.installId}/manifest.json")
        val original = manifest.readText()
        compose.resetDeterministicUiState()
        val store = compose.container().profileStore
        store.switchTo(SafetyProfile.ADVANCED)
        try {
            compose.navigateTo("setup/runtime")
            val ready = compose.activity.getString(R.string.settings_proot_verified)
            waitForRuntimeStatus(ready)
            compose.onNodeWithTag("settings-proot-verify").assertDoesNotExist()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            manifest.writeText("damaged")
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            val damaged = compose.activity.getString(R.string.settings_proot_damaged)
            waitForRuntimeStatus(damaged)
            compose.onNodeWithTag("settings-proot-status").assertTextContains(damaged, substring = true)
            org.junit.Assert.assertEquals("damaged", manifest.readText())
        } finally {
            manifest.writeText(original)
            store.switchTo(SafetyProfile.STANDARD)
        }
    }

    // ---------- helpers ----------

    private fun assertReadinessRuntimeReady() {
        val container = compose.container()
        compose.resetDeterministicUiState()
        container.profileStore.switchTo(SafetyProfile.ADVANCED)
        compose.navigateTo("setup/readiness")
        compose.onNodeWithTag("capability-readiness-goal-linux").performClick()
        waitRuntimeItem()
        compose.onNodeWithTag("capability-readiness-item-runtime").assertIsDisplayed()
        compose.onNodeWithTag("capability-readiness-action-manage-runtime").assertDoesNotExist()
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

    private fun waitForRuntimeStatus(text: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
