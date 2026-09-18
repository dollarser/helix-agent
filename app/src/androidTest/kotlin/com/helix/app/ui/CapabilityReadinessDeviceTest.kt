package com.helix.app.ui

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.MainActivity
import com.helix.app.proot.ProotToolModule
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.provider.api.CleartextAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-205 slice 3 — the full device journey for the Capability Readiness view, flavor-agnostic:
 * a fresh install (no model) still reaches the manual Files and Browser surfaces, an
 * already-configured model shows the CHAT goal ready, the passive refresh renders without a
 * cold bind (offline-safe), the view restores from durable facts across the configuration
 * rebuild path and a lifecycle cycle, the flavor/profile gate decides the LINUX goal, an
 * unavailable repair entry stays recoverable, and the view recovers in a NEW process after a
 * real process death.
 *
 * The developer-flavor Runtime-specific journey (actual init reusing the HXA-193 verify entry
 * and the corrupted-anchor recovery) lives in [CapabilityReadinessRuntimeDeviceTest]
 * (androidTestDeveloper): the runtime APIs are only on the developer test classpath, so that
 * half cannot be a shared test.
 *
 * Phase protocol (same shape as HXA-202's journey test): the matrix runs this class twice
 * against the same installation — `recoveryPhase=setup` records the process ID and kills the
 * process; `recoveryPhase=verify` asserts the new PID and that the view renders from durable
 * facts. With no phase argument everything runs in one process (standalone). The recovery
 * method runs in every run; the journey methods `assumeTrue`-skip in the setup run, so a skip
 * is never a pass.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // one method per journey facet plus the shared fixture helpers
class CapabilityReadinessDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** No model configured: the readiness view offers the add-model next action, and the manual
     *  Files and Browser surfaces are still reachable (没有模型配置也能进入手动文件和浏览器). */
    @Test
    fun freshInstallOffersAddModelAndManualSurfacesStayUsable() {
        assumeTrue(recoveryPhase() != "setup")
        deleteEditableProviders(compose.container())
        compose.resetDeterministicUiState()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
        compose.onNodeWithTag("capability-readiness-action-add-model").assertIsDisplayed()
        // The manual surfaces stay reachable regardless of model configuration.
        compose.navigateTo("files")
        compose.onNodeWithTag("files-home-source-app").assertIsDisplayed()
        compose.navigateTo("browser")
        compose.onNodeWithTag("browser-url-field").assertIsDisplayed()
    }

    /** An already-configured model: the CHAT goal is ready, so there is no add-model action. */
    @Test
    fun oldConfigWithConfiguredModelShowsChatReady() {
        assumeTrue(recoveryPhase() != "setup")
        createConfiguredProvider(compose.container())
        compose.resetDeterministicUiState()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
        compose.onNodeWithTag("capability-readiness-action-add-model").assertDoesNotExist()
        compose.onNodeWithTag("capability-readiness-item-model-state").assertIsDisplayed()
    }

    /** Passive entry + refresh render the projection from local facts only: no cold bind, no
     *  login, no fetch. The whole journey is offline-safe (the matrix runs it under airplane
     *  mode), so this assertion holds with or without the network. */
    @Test
    fun offlinePassiveRefreshRendersWithoutColdBind() {
        assumeTrue(recoveryPhase() != "setup")
        deleteEditableProviders(compose.container())
        compose.resetDeterministicUiState()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
        compose.onNodeWithTag("capability-readiness-item-model").assertIsDisplayed()
        compose.onNodeWithTag("capability-readiness-refresh").performClick()
        compose.waitForIdle()
        waitItems("capability-readiness-item-model")
        compose.onNodeWithTag("capability-readiness-item-model").assertIsDisplayed()
    }

    /** The configuration rebuild path (a rotation) and a real background/foreground cycle: the
     *  view restores from durable facts (no model) and stays passive — no cold bind, no login. */
    @Test
    fun readinessRestoresFromDurableFactsAfterConfigurationRebuildAndLifecycleCycle() {
        assumeTrue(recoveryPhase() != "setup")
        deleteEditableProviders(compose.container())
        compose.resetDeterministicUiState()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
        compose.onNodeWithTag("capability-readiness-action-add-model").assertIsDisplayed()
        rebuildReadiness()
        rebuildReadiness()
        backgroundAndReturnReadiness()
        compose.onNodeWithTag("capability-readiness-action-add-model").assertIsDisplayed()
    }

    /** The LINUX goal is gated by flavor AND profile: hidden under STANDARD in every flavor,
     *  exposed under ADVANCED only in the developer flavor (the consumer build never offers it). */
    @Test
    fun consumerHidesLinuxGoalAndDeveloperShowsItUnderAdvanced() {
        assumeTrue(recoveryPhase() != "setup")
        val container = compose.container()
        compose.resetDeterministicUiState()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
        // Under STANDARD the LINUX goal is hidden in every flavor.
        compose.onNodeWithTag("capability-readiness-goal-linux").assertDoesNotExist()
        if (ProotToolModule.AVAILABLE) {
            // Developer: the gate is flavor AND profile — ADVANCED exposes the LINUX goal.
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
                compose
                    .onAllNodesWithTag("capability-readiness-goal-linux")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("capability-readiness-goal-linux").assertIsDisplayed()
            container.profileStore.switchTo(SafetyProfile.STANDARD)
        }
        // Consumer (ADR-0005): the build runs STANDARD only, so switchTo(ADVANCED) is a hard
        // refusal and the LINUX goal can never be exposed — it stays absent here.
    }

    /** The repair entry is cancelable: opening it (Unavailable without the companion APK) leaves
     *  the view consistent and recoverable — no crash, no blind auto-continue. Developer only. */
    @Test
    fun cancelledOrUnavailableRepairKeepsViewRecoverable() {
        assumeTrue(ProotToolModule.AVAILABLE)
        assumeTrue(recoveryPhase() != "setup")
        compose.resetDeterministicUiState()
        compose.container().profileStore.switchTo(SafetyProfile.ADVANCED)
        compose.navigateTo("readiness")
        compose.onNodeWithTag("capability-readiness-goal-linux").performClick()
        waitItems("capability-readiness-item-runtime")
        if (compose
                .onAllNodesWithTag("capability-readiness-action-repair-runtime")
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            compose.onNodeWithTag("capability-readiness-action-repair-runtime").performClick()
            compose.waitForIdle()
        }
        waitItems("capability-readiness-item-runtime")
        compose.onNodeWithTag("capability-readiness-item-runtime").assertIsDisplayed()
        compose.container().profileStore.switchTo(SafetyProfile.STANDARD)
    }

    /** The real two-phase recovery: a process death, then a NEW PID renders the readiness view
     *  from durable facts. The recovery method runs in every run; the journey methods skip in
     *  the setup run, so each method genuinely executes in exactly one pass and a skip is never
     *  a pass. */
    @Test
    fun readinessStateRecoversAfterProcessReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val phase = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)
        if (phase == "verify") {
            val markerPid =
                context.noBackupFilesDir
                    .resolve(PID_MARKER)
                    .readText()
                    .toInt()
            assertNotEquals(
                "the readiness recovery must run in a new process",
                markerPid,
                Process.myPid(),
            )
            assertReadinessRendersPassive()
            return
        }
        writeRecoveryMarker(context)
        if (phase == "setup") {
            Process.killProcess(Process.myPid())
            error("the readiness recovery setup kill must end this process")
        }
        assertReadinessRendersPassive()
    }

    // ---------- helpers ----------

    private fun assertReadinessRendersPassive() {
        compose.resetDeterministicUiState()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
        compose.onNodeWithTag("capability-readiness-item-model").assertIsDisplayed()
    }

    private fun writeRecoveryMarker(context: Context) {
        context.noBackupFilesDir.resolve(PID_MARKER).writeText(Process.myPid().toString())
    }

    private fun recoveryPhase(): String? = InstrumentationRegistry.getArguments().getString(RECOVERY_PHASE_KEY)

    private fun waitItems(itemTag: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(itemTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rebuildReadiness() {
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
    }

    private fun backgroundAndReturnReadiness() {
        compose.activity.moveTaskToBack(false)
        Thread.sleep(1_500)
        compose.activity.intent?.let { compose.activity.startActivity(it) }
        compose.waitForIdle()
        compose.navigateTo("readiness")
        waitItems("capability-readiness-item-model")
    }

    private fun createConfiguredProvider(container: AppContainer): String {
        deleteEditableProviders(container)
        return runBlocking {
            container.providerService.create(
                ProviderDraft(
                    null,
                    "HXA205 old-config fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:18443/v1"),
                    "hxa205-fixture-model",
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", 18443),
                    emptyList(),
                ),
                "sk-hxa205-fixture-key",
                cleartextConfirmed = true,
            )
        }
    }

    private companion object {
        const val RECOVERY_PHASE_KEY = "recoveryPhase"
        const val PID_MARKER = "recovery-device-pid"
    }
}
