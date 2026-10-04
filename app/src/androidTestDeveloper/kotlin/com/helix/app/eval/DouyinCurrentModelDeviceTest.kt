package com.helix.app.eval

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
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Owner-opted single real-model attempt; preserves the original session and test evidence. */
class DouyinCurrentModelDeviceTest {
    @Test
    @Suppress("LongMethod") // Keep owner opt-in, the single attempt and state restoration in one lifecycle.
    fun installWithTheExistingModelOnce() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("helixDouyinInstall") == "true")
            val coolapk = args.getString("helixInstallApp") == "coolapk"
            val appName = if (coolapk) "酷安" else "抖音"
            val packageName = if (coolapk) "com.coolapk.market" else "com.ss.android.ugc.aweme"
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val original = requireNotNull(c.storage.sessions.find(requireNotNull(args.getString("helixSourceSession"))))
            val provider = requireNotNull(original.providerId)
            val model = requireNotNull(original.modelId)
            val center = AutomationPermissionCenter(app)
            val oldProfile = c.profileStore.profile
            val session =
                args.getString("helixResumeSession") ?: c.chatService.createSession("${appName}自主安装验证", provider, model)
            try {
                waitUntil(15_000) { center.serviceState() == AutomationServiceState.CONNECTED }
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
                c.pluginService.catalog.select(session, plugin.id, true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                check(center.authorizeConversation(session, emptySet(), true))
                c.chatService.openSession(session)
                c.chatService.setMode(AgentMode.ACT)
                waitUntil(10_000) { c.chatService.screen.value.openSessionId == session }
                println("INSTALL_TEST_SESSION=$session;APP=$appName;MODEL=$model")
                var prompt =
                    args.getString("helixInstallPrompt") ?: (
                        "帮我从${appName}官方来源安装$appName。可以使用已授权的 Mobile Use 操作浏览器和系统安装界面。" +
                            "如果设备下载目录已有官方安装包，可以核查复用；否则自行找到官方下载。" +
                            "安装后核查应用是否存在，不登录账号、不接受应用内用户协议、不操作已有微信数据。"
                    )
                // Owner requested continued attempts. These are explicit follow-up user messages,
                // not a production Harness retry or a substitute for any UI action.
                for (attempt in 1..4) {
                    val previousTurn =
                        c.storage.turns
                            .listBySession(session)
                            .lastOrNull()
                            ?.id
                    c.chatService.sendTestMessage(session, prompt)
                    waitUntil(1_200_000) {
                        c.storage.turns.listBySession(session).lastOrNull()?.let {
                            it.id != previousTurn && TurnState.valueOf(it.state).isTerminal
                        } == true
                    }
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .last()
                    println("INSTALL_RESULT=$appName;ATTEMPT=$attempt;STATE=${turn.state};ERROR=${turn.errorCode}")
                    if (runCatching { app.packageManager.getPackageInfo(packageName, 0) }.isSuccess ||
                        turn.state != TurnState.COMPLETED.name
                    ) {
                        break
                    }
                    prompt = "安装尚未完成，请继续执行本次请求。先观察并核实当前状态，再进行所需操作；" +
                        "若已安装则只核查。请实际调用下一步工具，不要只回复下一步计划。" +
                        "保持本次权限和不登录、不接受应用内协议、不操作微信的限制。"
                }
                assertTrue(
                    "$appName not installed; inspect preserved session $session",
                    runCatching { app.packageManager.getPackageInfo(packageName, 0) }.isSuccess,
                )
            } finally {
                c.chatService.stop()
                center.revokeConversation(session)
                c.profileStore.switchTo(oldProfile)
                c.chatService.openSession(original.id)
            }
        }

    private fun waitUntil(
        timeout: Long,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(condition()) { "Timed out after $timeout ms" }
    }
}
