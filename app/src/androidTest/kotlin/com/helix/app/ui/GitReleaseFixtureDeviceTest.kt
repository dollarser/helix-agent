package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Seed only. The owned host runner replaces the debug APK and exercises the real release UI. */
class GitReleaseFixtureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun prepareRepositoryForReleaseReplacement() {
        val phase = InstrumentationRegistry.getArguments().getString("gitBoundaryPhase")
        // Ordinary suites cannot replace their own APK; the owned two-APK runner supplies this phase.
        assumeNotNull(phase)
        check(phase == "prepare")
        compose.resetDeterministicUiState()
        val root = File(compose.activity.filesDir, "workspaces/app/git-release-fixture")
        check(!root.exists())
        check(root.mkdirs())
        GitReadOnlyBoundaryDeviceTest().seed(root, 43281)
        assertFalse(File(root, "executed").exists())
    }
}
