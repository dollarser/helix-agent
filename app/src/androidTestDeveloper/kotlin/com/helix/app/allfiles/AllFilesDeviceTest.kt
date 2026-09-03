package com.helix.app.allfiles

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.resolveFileScopePath
import com.helix.feature.files.allfiles.AllFilesRootCatalog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/**
 * HXA-045 device acceptance (verification matrix row, `:app:connectedDeveloperDebugAndroidTest` on
 * the API 36 emulator): the all-files capability's live system probe, the Helix-roots registry, and
 * the scope-boundary fail-closed contract — proven against the REAL platform, not mocks.
 *
 * The security property under test (roadmap HXA-045): EVEN WITH the system
 * `MANAGE_EXTERNAL_STORAGE` grant, a path outside the roots the user explicitly enabled is still
 * refused. [AllFilesModule.resolveScopeRoot] is the exact seam the production [AppContainer] wires
 * into its scope resolver, and it is the ONLY thing that can turn a model-visible `af-<root>` id
 * into a real path. It fails closed on (a) a scope the user did not enable, (b) a lost system
 * grant, and (c) a path that would escape the enabled root — so "all files" never becomes "the
 * whole phone" (doc 09 section 4.1; doc 10).
 */
@RunWith(AndroidJUnit4::class)
class AllFilesDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The exact resolver the production AppContainer builds for all-files scopes. */
    private val scopeResolver =
        ScopeRootResolver { scopeId ->
            AllFilesModule.resolveScopeRoot(scopeId) ?: throw ScopeNotAvailable("unknown scope: $scopeId")
        }

    private fun downloadScopeId() = AllFilesRootCatalog.scopeId("download")

    @Before
    fun setUp() {
        // Idempotent; the running app's Application already initialized the same store path, so
        // the assertions below see the same persisted registry.
        AllFilesModule.init(context)
    }

    // ── Probe agreement (doc 09 section 4.1 step 3: verify isExternalStorageManager live) ──────

    @Test
    fun scopeResolutionAgreesWithTheLiveSystemGrant() {
        // Record the download root (enableRoot persists the resolved path regardless of the
        // grant); resolution must then track the LIVE system grant exactly.
        AllFilesModule.enableRoot("download")
        val resolved = AllFilesModule.resolveScopeRoot(downloadScopeId())
        if (Build.VERSION.SDK_INT < 30) {
            // `Environment.isExternalStorageManager` does not exist below API 30 (calling it
            // throws NoSuchMethodError); MANAGE_EXTERNAL_FILES is structurally absent there,
            // so the probe is false and NOTHING resolves — the same fail-closed contract as
            // the un-granted case.
            assertNull("API < 30: the grant cannot exist, so a recorded root must NOT resolve", resolved)
        } else if (Environment.isExternalStorageManager()) {
            assertNotNull("granted: an enabled root must resolve to a real path", resolved)
            assertTrue(
                "the resolved root must live under public storage",
                resolved.toString().startsWith("/storage/"),
            )
        } else {
            assertNull("un-granted: a recorded root must NOT resolve (fail closed)", resolved)
        }
    }

    // ── Fail closed even WITH the system grant (the HXA-045 core property) ──────────────────────

    @Test
    fun aScopeTheUserDidNotEnableNeverResolves() {
        // "music" is disabled here; with or without the system grant its scope has no root, so the
        // resolver refuses it and scope-path resolution fails closed.
        AllFilesModule.disableRoot("music")
        assertNull(
            "an un-enabled root has no resolvable scope even when the system grant is on",
            AllFilesModule.resolveScopeRoot(AllFilesRootCatalog.scopeId("music")),
        )
        assertThrows(ScopeNotAvailable::class.java) {
            resolveFileScopePath(
                FileScopePath(AllFilesRootCatalog.scopeId("music"), "input/a.txt"),
                scopeResolver,
            )
        }
    }

    @Test
    fun aNonAllFilesScopeIdIsNotHandledByThisModule() {
        // The app-private "app" scope and a SAF id are outside the all-files module's `af-` surface.
        assertNull(AllFilesModule.resolveScopeRoot("app"))
        assertNull(AllFilesModule.resolveScopeRoot("saf-abc123"))
    }

    @Test
    fun aPathEscapingTheEnabledRootIsRefusedAtTheStringLayer() {
        // `..` above the scope root is rejected at FileScopePath construction (before any I/O), so a
        // model reference can never name a file outside the enabled root.
        assertThrows(IllegalArgumentException::class.java) { FileScopePath(downloadScopeId(), "../sibling") }
        assertThrows(IllegalArgumentException::class.java) { FileScopePath(downloadScopeId(), "a/../../b") }
    }

    @Test
    fun anEnabledRootResolvesAndStaysContainedWithinTheGrant() {
        Assume.assumeTrue(
            "the happy path needs the API 30+ grant (the run harness grants it via appops); " +
                "the SDK check guards the call itself — the method is absent below API 30 — " +
                "and fail-closed branches are proven unconditionally by the other tests",
            Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager(),
        )
        AllFilesModule.enableRoot("download")
        val root = AllFilesModule.resolveScopeRoot(downloadScopeId())
        assertNotNull(root)
        Files.createDirectories(root) // ensure the public root exists for toRealPath
        val resolved = resolveFileScopePath(FileScopePath(downloadScopeId(), "input/a.txt"), scopeResolver)
        assertTrue(
            "the resolved path must stay under the enabled root",
            resolved.toString().startsWith(root.toString()),
        )
    }

    // ── Consent screen (doc 09 section 4.1: explanation + system-settings jump + live state) ────

    @Test
    fun consentScreenShowsLiveStateAndTheSettingsJumpWhenDenied() {
        composeRule.resetDeterministicUiState() // STANDARD profile + gate dismissed
        composeRule.navigateTo("permissions")

        composeRule.onNodeWithTag("screen-permissions-allfiles").assertIsDisplayed()
        // The honest explanation is always present — it never claims "the whole phone".
        composeRule.onNodeWithTag("allfiles-explanation").assertIsDisplayed()
        // The live system-state text mirrors the real platform (re-read from the capability center).
        if (Build.VERSION.SDK_INT < 30) {
            // API < 30: the grant does not exist on the platform — the screen reports the
            // state as unavailable and offers no settings jump (there is no settings screen
            // for a permission the system does not have).
            composeRule
                .onNodeWithText("系统状态：此系统/版本不提供（API 低于 30）")
                .assertIsDisplayed()
            assertTrue(
                "no settings jump below API 30 (there is no screen to jump to)",
                composeRule.onAllNodesWithTag("allfiles-open-settings").fetchSemanticsNodes().isEmpty(),
            )
        } else if (Environment.isExternalStorageManager()) {
            composeRule.onNodeWithText("系统状态：已授权").assertIsDisplayed()
        } else {
            composeRule.onNodeWithText("系统状态：未授权").assertIsDisplayed()
            // The system-settings jump is offered only while the grant is missing.
            composeRule.onNodeWithTag("allfiles-open-settings").assertIsDisplayed()
        }
        // STANDARD (set by resetDeterministicUiState): the ADVANCED gate note is shown, and a root row exists.
        composeRule.onNodeWithTag("allfiles-advanced-required").assertIsDisplayed()
        composeRule.onNodeWithTag("allfiles-root-download").assertIsDisplayed()
    }
}
