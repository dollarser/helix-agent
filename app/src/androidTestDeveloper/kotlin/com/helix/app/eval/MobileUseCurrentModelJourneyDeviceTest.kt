package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner-authorized real model run against an isolated synthetic screen, never a real account. */
class MobileUseCurrentModelJourneyDeviceTest {
    @Test
    @Suppress("LongMethod") // Preserve original configuration and retain the complete model trace together.
    fun modelObservesTypesScrollsAndVerifiesWithoutHostActions() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            check(args.getString("helixRealModel") == "true") { "Explicit model opt-in required" }
            val installing = args.getString("helixInstallFixture") == "true"
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val original = requireNotNull(c.storage.sessions.find(requireNotNull(args.getString("helixSourceSession"))))
            val session =
                c.chatService.createSession(
                    "Mobile Use 重构真实模型回归",
                    requireNotNull(original.providerId),
                    requireNotNull(original.modelId),
                )
            val center = AutomationPermissionCenter(app)
            val profile = c.profileStore.profile
            val globals = MobileUseGlobalScopeFixture(app)
            try {
                await(15_000) { center.serviceState() == AutomationServiceState.CONNECTED }
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
                c.pluginService.catalog.select(session, plugin.id, true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                com.helix.app.eval
                    .selectMobileUseForTest(session, emptySet(), true)
                c.chatService.openSession(session)
                c.chatService.setMode(AgentMode.ACT)
                await(10_000) { c.chatService.screen.value.openSessionId == session }
                app.startActivity(
                    Intent()
                        .setComponent(
                            ComponentName("${app.packageName}.test", fixtureActivity(installing)),
                        ).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                        ).putExtra("modelJourney", true),
                )
                SystemClock.sleep(1000)
                println("MOBILE_MODEL_SESSION=$session;MODEL=${original.modelId}")
                c.chatService.sendTestMessage(
                    session,
                    prompt(installing),
                )
                await(600_000) {
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull()
                        ?.let { TurnState.valueOf(it.state).isTerminal } ==
                        true
                }
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .last()
                println("MOBILE_MODEL_RESULT=${turn.state};ERROR=${turn.errorCode}")
                verifyTarget(app, center, session, installing)
                assertTrue("Turn did not complete: ${turn.state}", turn.state == TurnState.COMPLETED.name)
            } finally {
                c.chatService.stop()
                com.helix.app.eval
                    .deselectMobileUseForTest(session)
                globals.close()
                c.profileStore.switchTo(profile)
                c.chatService.openSession(original.id)
            }
        }

    private fun fixtureActivity(installing: Boolean) =
        if (installing) {
            AutomationInstallSourceActivity::class.java.name
        } else {
            AutomationEvaluationActivity::class.java.name
        }

    private fun prompt(installing: Boolean) =
        if (installing) {
            "请使用 Mobile Use 完成当前系统安装界面中的 Helix Install Fixture 安装。可以开启本次测试来源的安装许可并点" +
                "击系统安装确认，安装后用应用列表或完成界面核查成功。不安装其他应用、不登录、不使用终端。"
        } else {
            "请使用 Mobile Use 完成当前手机测试页：把标注“测试输入”的输入框替换为“你好 Helix”，向下滚动找到“完成测试”按钮并点击一" +
                "次，再观察确认页面显示“TEST_DONE:你好 Helix”。只操作这个测试页面，不操作其他应用，不使用 bash 或终端，不登录账号。完" +
                "成后报告实际结果。"
        }

    private fun verifyTarget(
        app: HelixApplication,
        center: AutomationPermissionCenter,
        session: String,
        installing: Boolean,
    ) {
        if (installing) {
            assertTrue(
                "Fixture not installed; retained session $session",
                runCatching {
                    app.packageManager.getPackageInfo("com.helix.validation.installfixture", 0)
                }.isSuccess,
            )
        } else {
            val snapshot = evaluationAutomationPort(center, session).snapshot().snapshot
            assertTrue(
                "Actual target not reached; retained session $session",
                snapshot?.nodes?.any { it.text == "TEST_DONE:你好 Helix" } == true,
            )
        }
    }

    private fun await(
        timeout: Long,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        check(condition()) { "Timed out after $timeout ms" }
    }
}
