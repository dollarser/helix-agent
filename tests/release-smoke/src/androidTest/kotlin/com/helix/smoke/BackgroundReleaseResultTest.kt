package com.helix.smoke

import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Public framework/UI observations only; no root, target database or private target API. */
class BackgroundReleaseResultTest {
    @Test fun ownedDisplayWasReleasedBeforeAnyTestHelperCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedDisplayId")).toInt()
        require(id > 0)
        val displays = instrumentation.context.getSystemService(DisplayManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (displays.getDisplay(id) != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertNull("Original background display still exists", displays.getDisplay(id))
        assertEquals(1, instrumentation.context.getSharedPreferences("release-fixture-facts", 0).getInt("clicks", -1))
    }

    @Test fun completedConversationSurvivesProcessRestartWithoutAnotherFixtureClick() {
        val ui = ReleaseUiTestSupport()
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        ui.ready()
        ui.activate("应用导航", "App navigation")
        ui.activate("当前会话", "Current conversation")
        ui.await("STABILITY_R8_VERIFIED", timeoutMillis = 15_000)
        verifyArtifactPreview(ui)
        ui.restartTarget()
        ui.await("STABILITY_R8_VERIFIED", timeoutMillis = 15_000)
        val context = InstrumentationRegistry.getInstrumentation().context
        assertEquals(1, context.getSharedPreferences("release-fixture-facts", 0).getInt("clicks", -1))
    }

    private fun verifyArtifactPreview(ui: ReleaseUiTestSupport) {
        val pattern = Regex("成果 [0-9]+ 项 · .*|[0-9]+ artifacts · .*")
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var label: String? = null
        while (label == null && SystemClock.elapsedRealtime() < deadline) {
            label = ui.nodes().mapNotNull { it.text?.toString() }.firstOrNull(pattern::matches)
            if (label == null) SystemClock.sleep(100)
        }
        ui.activate(requireNotNull(label) { "No persisted Turn-owned artifact entry" })
        ui.activate("r8-skill.txt")
        ui.await("R8_SKILL_CONTENT")
        ui.back()
        ui.back()
        ui.await("STABILITY_R8_VERIFIED")
    }
}
