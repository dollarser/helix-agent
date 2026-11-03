package com.helix.smoke

import android.app.UiAutomation
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Public UI only against the isolated non-debuggable R8 target; synthetic loopback provider. */
class CurrentReleaseJourneyTest {
    private val ui = ReleaseUiTestSupport()
    private val runId =
        requireNotNull(InstrumentationRegistry.getArguments().getString("r8RunId"))
            .also { require(it.matches(Regex("[0-9]+"))) }
    private val marker = "R8_CURRENT_VERIFIED_$runId"
    private val expectedContent = "R8_CURRENT_CONTENT_$runId"

    @Test fun manualPageAndTaskSkillSurviveOwnerReplacementAndResultsSurviveProcessRestart() {
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.restartTarget()
        openBrowser()
        ui.enterText("data:text/html,<title>R8 Manual current</title><body>R8_MANUAL_CURRENT</body>")
        ui.activate("前往", "Go")
        ui.await("R8_MANUAL_CURRENT")
        ui.activate("应用导航", "App navigation")
        ui.activate("当前会话", "Current conversation")
        prepareNewConversation()
        ui.enterText("R8CURRENT_SYNTHETIC R8RUN=$runId Create and use only the owned synthetic Skill.", focus = true)
        ui.activate("发送", "Send")
        ui.await(marker, timeoutMillis = 180_000)
        previewArtifact()
        openBrowser()
        ui.await("R8_MANUAL_CURRENT")
        ui.activate("标签页", "Tabs")
        ui.activate("R8 Task current")
        ui.await("返回原会话", "Return to original conversation")
        try {
            val rotation =
                if (ui.context.resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
                ) {
                    UiAutomation.ROTATION_FREEZE_90
                } else {
                    UiAutomation.ROTATION_FREEZE_0
                }
            check(automation.setRotation(rotation))
            ui.await("重新打开页面", "Reopen page")
            SystemClock.sleep(1_000)
            ui.await("重新打开页面", "Reopen page")
            ui.activate("重新打开页面", "Reopen page")
            ui.await("R8_TASK_CURRENT")
            ui.activate("返回原会话", "Return to original conversation")
            ui.await(marker)
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
        verifyPersistedResult()
    }

    internal fun verifyPersistedResult() {
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        ui.restartTarget()
        ui.await(marker, timeoutMillis = 15_000)
        previewArtifact()
    }

    private fun prepareNewConversation() {
        ui.activate("新建会话", "New conversation")
        if (ui.node("需要批准 ▾", "Approval required ▾") != null) {
            ui.activate("需要批准 ▾", "Approval required ▾")
            ui.activate("完全访问", "Full access")
        }
        ui.await("完全访问 ▾", "Full access ▾")
        if (ui.node("选择模型 ▾", "Select model ▾") != null) {
            ui.activate("选择模型 ▾", "Select model ▾")
            ui.activate("stability-fixture")
            ui.activate("保存", "Save")
        }
        ui.await("stability-fixture ▾")
        ui.await("执行", "Act")
    }

    private fun openBrowser() {
        ui.activate("应用导航", "App navigation")
        if (ui.node("浏览器", "Browser") == null) {
            ui.scrollTo("工具", "Tools")
            ui.activate("工具", "Tools")
        }
        ui.scrollTo("浏览器", "Browser")
        ui.activate("浏览器", "Browser")
    }

    private fun previewArtifact() {
        val pattern = Regex("成果 [0-9]+ 项 · .*|[0-9]+ artifacts · .*")
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var label: String? = null
        while (label == null && SystemClock.elapsedRealtime() < deadline) {
            label = ui.nodes().mapNotNull { it.text?.toString() }.firstOrNull(pattern::matches)
            if (label == null) SystemClock.sleep(100)
        }
        ui.activate(requireNotNull(label) { "No actual Turn artifact entry" })
        ui.scrollTo("r8-current.txt")
        ui.activate("r8-current.txt")
        ui.await(expectedContent)
        ui.back()
        ui.back()
        ui.await(marker)
    }
}

/** Host cover-installs the same-signature candidate before this check; never submits another task. */
class CurrentReleaseUpgradeTest {
    @Test fun verifiedConversationAndArtifactRemainAfterCoverInstall() {
        CurrentReleaseJourneyTest().verifyPersistedResult()
    }
}
