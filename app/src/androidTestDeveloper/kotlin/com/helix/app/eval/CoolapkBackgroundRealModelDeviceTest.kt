package com.helix.app.eval

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.internal.PrefsLineStore
import com.helix.app.openTestSessionControl
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit owner-requested public browsing. The real Agent chooses every Coolapk action. */
class CoolapkBackgroundRealModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught") // Retain failure evidence, rethrow, and restore in finally.
    fun configuredAgentBrowsesCoolapkOnBackgroundDisplay() =
        runBlocking(Dispatchers.IO) {
            val args = InstrumentationRegistry.getArguments()
            org.junit.Assume.assumeTrue(args.getString("helixCoolapkRealModel") == "true")
            check(app.packageName == "com.helix.agent.developer")
            app.runtimeRecoveryReady.await()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "An owner task is active; do not disturb it" }
            val provider = BuiltInModel.PROVIDER_ID
            check(
                c.storage.providerConfigs
                    .resolve(provider)
                    .model == BuiltInModel.MODEL_ID,
            )
            val trial = requireNotNull(args.getString("helixTrial"))
            require(trial.matches(Regex("[a-z0-9-]+")))
            val profile = c.profileStore.profile
            val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
            val oldScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
            val oldExecution = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            val budgets = c.chatService.runControl.value.budgets
            val session = c.chatService.createSession("酷安后台虚拟屏实测 $trial", provider, BuiltInModel.MODEL_ID)
            val started = SystemClock.elapsedRealtime()
            var passed = false
            var failure: String? = null
            try {
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
                if (args.getString("helixCoolapkUseExistingScope") == "true") {
                    val configured = requireNotNull(store.globalConfiguration())
                    check(configured.scope.permitsPackage("com.coolapk.market") && configured.shareScreens) {
                        "Use the owner configured scope without broadening it"
                    }
                } else {
                    store.configureGlobal(setOf("com.coolapk.market"), false, shareScreens = true)
                }
                store.configureExecution(background = true, allowMoveFromMain = true)
                c.pluginService.setEnabled(plugin.id, true)
                c.pluginService.catalog.select(session, plugin.id, true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.chatService.openTestSessionControl(session, AgentMode.ACT, budgets)
                val launch = requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
                app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                report("COOLAPK_REAL_SESSION=$session;TRIAL=$trial;MODEL=${BuiltInModel.MODEL_ID}")
                val selectedPrompt =
                    if (args.getString("helixCoolapkScenario") ==
                        "feed-scroll"
                    ) {
                        feedScrollPrompt
                    } else {
                        prompt
                    }
                val submission = ChatSubmission(session, 0, "coolapk-$trial", selectedPrompt)
                val admission = c.chatService.sendSubmission(submission).await()
                check(admission.outcome is ChatSubmissionOutcome.Accepted) {
                    "Input not accepted: ${admission.outcome}"
                }
                awaitActualTerminal(session)
                passed = verifyMinimumExecution(session)
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
                throw error
            } finally {
                withContext(NonCancellable) {
                    try {
                        stopIfStillRunning(session)
                        saveClosedLoopEvidence(
                            app,
                            c,
                            ClosedLoopCaseEvidence(
                                session,
                                "coolapk",
                                trial,
                                BuiltInModel.MODEL_ID,
                                started,
                                passed,
                                failure,
                            ),
                        )
                    } finally {
                        lines.setLines(MobileUseGrantStore.CONFIG_KEY, oldScope)
                        lines.setLines(MobileUseGrantStore.EXECUTION_KEY, oldExecution)
                        c.pluginService.catalog.select(session, plugin.id, false)
                        c.pluginService.setEnabled(plugin.id, plugin.enabled)
                        c.profileStore.switchTo(profile)
                    }
                }
            }
            assertTrue("Inspect retained real-model trace and screenshots for $trial", passed)
        }

    private suspend fun awaitActualTerminal(session: String) {
        var last = ""
        while (true) {
            c.chatService.toolVisionConsent.pending.value.filter { it.sessionId == session }.forEach {
                c.chatService.toolVisionConsent.respond(it.id, true)
            }
            val turn =
                c.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                val status =
                    "${turn.state};models=${c.storage.modelCalls.listByTurn(turn.id).size};" +
                        "tools=${c.storage.toolCalls.listByTurn(turn.id).size}"
                if (status != last) {
                    report("COOLAPK_PROGRESS=$status")
                    last = status
                }
                if (TurnState.valueOf(turn.state).isTerminal) return
            }
            delay(250)
        }
    }

    private suspend fun stopIfStillRunning(session: String) {
        val turn =
            c.storage.turns
                .listBySession(session)
                .lastOrNull() ?: return
        if (!TurnState.valueOf(turn.state).isTerminal) {
            c.chatService.stopTurn(turn.id)
            while (c.storage.turns
                    .resolve(turn.id)
                    .state == TurnState.CANCELLING.name
            ) {
                delay(100)
            }
        }
    }

    private fun verifyMinimumExecution(session: String): Boolean {
        val turn =
            c.storage.turns
                .listBySession(session)
                .last()
        val calls = c.storage.toolCalls.listByTurn(turn.id)
        val results =
            calls
                .mapNotNull { c.storage.toolResults.byToolCall(it.callId) }
                .mapNotNull { c.storage.toolResults.readContent(it) }
        return turn.state == TurnState.COMPLETED.name &&
            calls.any { it.name == "ui.launch" && it.state == "COMPLETED" } &&
            results.any { it.contains("\"pixelsAttached\":true") } &&
            results.any { it.contains("文件管理器") }
    }

    private fun report(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "$value\n")
            },
        )
    }

    private val feedScrollPrompt =
        "请用手机操作在后台虚拟屏打开酷安（com.coolapk.market），实际测试浏览和搜索。" +
            "先截图首页，记录第一条可见的公开信息流内容；在信息流向下滚动，让新的内容出现，再截图核对变化。" +
            "根据工具返回的实际能力选择滚动方向或视觉手势，不要把横向切换标签当作纵向滚动。" +
            "然后搜索“文件管理器”，读出前三条公开结果名称并截图，最后返回酷安首页。" +
            "搜索结果若一屏显示完，不必在那里反复滚动。请实际操作并如实汇报，未确认的结果明确说明。" +
            "只操作酷安后台屏，不操作主屏Helix；不要下载、安装、登录、点赞、评论、发帖、查看个人资料或修改设置。" +
            "遇新协议或系统权限说明阻塞，不代为确认。不使用OCR，需要视觉时直接理解截图。" +
            "结果未知的动作不要重放。"

    private val prompt =
        "请在后台虚拟屏打开酷安（com.coolapk.market），实际完成一次常规浏览测试：" +
            "观察当前页面，搜索“文件管理器”，读取前三条公开搜索结果的名称，" +
            "向下滚动一次并确认内容变化，最后返回刚打开时的酷安主页面。" +
            "搜索结果页和最终页面都请截图检查。请通过 Mobile Use 实际操作，不要仅给操作建议。" +
            "只操作酷安的后台屏，不操作或占用主屏上的 Helix。" +
            "不要下载、安装、登录注册、点赞评论发帖、查看个人资料或修改设置；" +
            "如果必须接受新协议或授予新的系统权限，说明阻塞，不代为确认。" +
            "不要使用 OCR；需要视觉时直接理解截图。目标过期先重新观察，不重放结果未知的动作。" +
            "最终如实汇报完成的操作、前三条结果和未完成项，不要把调用成功当业务完成。"
}
