package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.app.provider.BuiltInModel
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.backend.RootAutomationBackend
import com.helix.extensions.mobileuse.automation.backend.ShizukuAutomationBackend
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.tools.deviceaccess.DeviceAccess
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner-authorized real model run against an isolated synthetic screen, never a real account. */
class MobileUseCurrentModelJourneyDeviceTest {
    @Test
    // One owned run retains profile, execution and restoration together.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun modelObservesTypesScrollsAndVerifiesWithoutHostActions() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val optIn = args.getString("helixRealModel")
            org.junit.Assume.assumeTrue("Requires explicit real-model profile", optIn != null)
            require(optIn == "true") { "Explicit model opt-in required" }
            val installing = args.getString("helixInstallFixture") == "true"
            val background = args.getString("helixBackgroundModel") == "true"
            val root = args.getString("helixRootHelper") == "true"
            val computerAdb = args.getString("helixAdbHelper") == "true"
            require(!(root && computerAdb))
            require(!background || (!installing && (computerAdb || root)))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            app.runtimeRecoveryReady.await()
            c.toolPipeline.registry.snapshot()
            if (args.getString("helixAdbHelper") == "true") awaitComputerAdbHelper()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "Daily package has active work" }
            val previous = c.chatService.screen.value.openSessionId
            val source = args.getString("helixSourceSession")?.let { requireNotNull(c.storage.sessions.find(it)) }
            val provider = source?.let { requireNotNull(it.providerId) } ?: BuiltInModel.PROVIDER_ID
            val model = source?.let { requireNotNull(it.modelId) } ?: BuiltInModel.MODEL_ID
            check(
                c.providerService.rows.value.any {
                    it.id == provider && it.chatSelectable &&
                        model in it.conversationModels
                },
            ) {
                "Configured model unavailable"
            }
            val session =
                c.chatService.createSession(
                    "Mobile Use 重构真实模型回归",
                    provider,
                    model,
                )
            val center =
                AutomationPermissionCenter(
                    app,
                    ShizukuAutomationBackend(app),
                    RootAutomationBackend { DeviceAccess.root("mobile-use") },
                    adb = { com.helix.app.automation.AutomationModule.computerAdbBackend },
                )
            val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
            val savedScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
            val savedRoute = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
            val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
            val fixture = "${app.packageName}.test"
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            val started = SystemClock.elapsedRealtime()
            var passed = false
            try {
                store.configureGlobal(if (installing) emptySet() else setOf(fixture), installing)
                store.configureExecution(background = background, allowMoveFromMain = false)
                check(c.profileStore.profile == SafetyProfile.ADVANCED)
                c.pluginService.setEnabled(plugin.id, true)
                if (root) awaitOwnerGrantedRootBackend()
                await(15_000) { center.preferredBackend(AutomationDeviceOperation.SNAPSHOT) != null }
                if (args.getString("helixAdbHelper") == "true") {
                    assertTrue(center.preferredBackend(AutomationDeviceOperation.SNAPSHOT)?.name == "ADB")
                }
                if (root) assertTrue(center.preferredBackend(AutomationDeviceOperation.SNAPSHOT)?.name == "ROOT")
                c.pluginService.catalog.select(session, plugin.id, true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(
                        if (installing) SessionPermissionMode.FULL_ACCESS else SessionPermissionMode.APPROVAL_REQUIRED,
                    ),
                    System.currentTimeMillis(),
                )
                com.helix.app.eval
                    .selectMobileUseForTest(session, if (installing) emptySet() else setOf(fixture), installing)
                c.chatService.openSession(session)
                c.chatService.setMode(AgentMode.ACT)
                await(10_000) { c.chatService.screen.value.openSessionId == session }
                if (!background) {
                    app.startActivity(
                        Intent()
                            .setComponent(
                                ComponentName("${app.packageName}.test", fixtureActivity(installing)),
                            ).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                            ).putExtra("modelJourney", true),
                    )
                }
                SystemClock.sleep(1000)
                println("MOBILE_MODEL_SESSION=$session;MODEL=$model")
                c.chatService.sendTestMessage(
                    session,
                    prompt(installing, background),
                )
                await(600_000) {
                    if (!installing) approveOwnedFixtureCalls(app, session, fixture)
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
                verifyTarget(app, center, session, installing, background)
                assertTrue("Turn did not complete: ${turn.state}", turn.state == TurnState.COMPLETED.name)
                passed = true
            } finally {
                finishOwnedCase(c.storage, c.chatService, session, {}, {
                    saveJourney(app, session, started, passed)
                }, { stopped ->
                    if (stopped) {
                        await(15_000) { c.turnEngine?.liveExecution?.hasActive(session) != true }
                        com.helix.app.eval
                            .deselectMobileUseForTest(session)
                        lines.setLines(MobileUseGrantStore.CONFIG_KEY, savedScope)
                        lines.setLines(MobileUseGrantStore.EXECUTION_KEY, savedRoute)
                        c.pluginService.setEnabled(plugin.id, plugin.enabled)
                        if (root) DeviceAccess.disconnectRoot("mobile-use")
                        if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
                    }
                })
            }
        }

    private fun fixtureActivity(installing: Boolean) =
        if (installing) {
            AutomationInstallSourceActivity::class.java.name
        } else {
            AutomationEvaluationActivity::class.java.name
        }

    private fun prompt(
        installing: Boolean,
        background: Boolean,
    ) = if (background) {
        "请使用 Mobile Use 在后台任务屏打开专用测试应用 com.helix.agent.developer.test（Helix background fixture）。" +
            "把 Background text 输入框替换为“你好 Helix”，向下滚动页面内的 SCROLL_ROW 列表，点击 Increment 一次，" +
            "再观察确认 BACKGROUND_COUNT:1 和输入文字。只操作该测试应用，不使用终端，不登录、不操作其他应用。报告实际结果。"
    } else if (installing) {
        "请使用 Mobile Use 完成当前系统安装界面中的 Helix Install Fixture 安装。可以开启本次测试来源的安装许可并点" +
            "击系统安装确认，安装后用应用列表或完成界面核查成功。不安装其他应用、不登录、不使用终端。"
    } else {
        "请使用 Mobile Use 完成当前手机测试页：把标注“测试输入”的输入框替换为“你好 Helix”，向下滚动找到“完成测试”按钮并点击一" +
            "次，再观察确认页面显示“TEST_DONE:你好 Helix”。只操作这个测试页面，不操作其他应用，不使用 bash 或终端，不登录账号。完" +
            "成后停留在测试页面并报告实际结果，不返回 Helix。"
    }

    private fun verifyTarget(
        app: HelixApplication,
        center: AutomationPermissionCenter,
        session: String,
        installing: Boolean,
        background: Boolean,
    ) {
        if (background) {
            verifyBackgroundTarget(app, session)
        } else if (installing) {
            assertTrue(
                "Fixture not installed; retained session $session",
                runCatching {
                    app.packageManager.getPackageInfo("com.helix.validation.installfixture", 0)
                }.isSuccess,
            )
        } else {
            val uri = android.net.Uri.parse("content://${app.packageName}.test.capability-state/result")
            check(
                app.contentResolver.query(uri, null, null, null, null)?.use {
                    it.moveToFirst() && it.getString(0) == "TEST_DONE:你好 Helix" && it.getInt(1) == 1 &&
                        it.getInt(it.getColumnIndexOrThrow("scrollY")) > 0
                } == true,
            ) { "Independent target did not record one completion after scrolling" }
            val snapshot = evaluationAutomationPort(center, session).snapshot().snapshot
            assertTrue(
                "Actual target not reached; retained session $session",
                snapshot?.nodes?.any { it.text == "TEST_DONE:你好 Helix" } == true,
            )
        }
    }

    private fun verifyBackgroundTarget(
        app: HelixApplication,
        session: String,
    ) {
        val uri = android.net.Uri.parse("content://${app.packageName}.test.capability-state/background")
        val facts =
            app.contentResolver.query(uri, null, null, null, null)?.use {
                check(it.moveToFirst())
                JSONObject()
                    .put("display", it.getInt(0))
                    .put("clicks", it.getInt(1))
                    .put("scrollY", it.getInt(it.getColumnIndexOrThrow("scrollY")))
            } ?: error("Independent background target unavailable")
        check(facts.getInt("display") > 0 && facts.getInt("clicks") == 1 && facts.getInt("scrollY") > 0)
        val c = app.appContainer
        val turn =
            c.storage.turns
                .listBySession(session)
                .last()
        val calls = c.storage.toolCalls.listByTurn(turn.id)
        val observedAt = calls.indexOfLast { it.name == "ui.snapshot" && it.state == "COMPLETED" }
        val inputs = setOf("ui.launch", "ui.set_text", "ui.scroll", "ui.click", "ui.gesture", "ui.click_match")
        val actedAt = calls.indexOfLast { it.name in inputs }
        check(observedAt > actedAt) { "Model did not observe after its final input" }
        // Read the model's actual live-Turn observation. A fabricated post-completion call has
        // no active background authority and must not be used to bypass that production guard.
        val result = requireNotNull(c.storage.toolResults.byToolCall(calls[observedAt].callId))
        val snapshot = JSONObject(requireNotNull(c.storage.toolResults.readContent(result)))
        val nodes = snapshot.getJSONArray("nodes")
        check((0 until nodes.length()).any { nodes.getJSONObject(it).optString("text") == "BACKGROUND_COUNT:1" })
        val textVerified =
            (0 until nodes.length()).any {
                val node = nodes.getJSONObject(it)
                node.optBoolean("editable") && node.optString("text") == "你好 Helix"
            }
        check(textVerified)
        facts.put("observedAfterFinalInput", true).put("deviceModel", android.os.Build.MODEL)
        File(app.filesDir, "validation/emulator-acceptance/background-model-target.json").apply {
            parentFile!!.mkdirs()
            writeText(facts.toString())
        }
    }

    private fun approveOwnedFixtureCalls(
        app: HelixApplication,
        session: String,
        fixture: String,
    ) {
        val c = app.appContainer
        c.storage.turns.listBySession(session).flatMap { c.storage.toolCalls.listByTurn(it.id) }.forEach { call ->
            val approval = c.storage.approvals.byToolCall(call.callId) ?: return@forEach
            if (approval.decision != null) return@forEach
            val args = JSONObject(call.argsJson)
            val permitted =
                when (call.name) {
                    "ui.set_text" -> args.optString("text") == "你好 Helix" && !args.optBoolean("submit", false)

                    "ui.launch" -> args.optString("packageName") == fixture

                    "ui.click", "ui.click_match", "ui.scroll", "ui.gesture", "ui.snapshot", "ui.find", "ui.wait",
                    "ui.device", "ui.screenshot", "ui.apps",
                    -> true

                    else -> false
                }
            if (permitted) c.chatService.approveApproval(approval.id) else c.chatService.denyApproval(approval.id)
        }
    }

    private fun saveJourney(
        app: HelixApplication,
        session: String,
        started: Long,
        passed: Boolean,
    ) {
        val c = app.appContainer
        val turns = c.storage.turns.listBySession(session)
        val trace =
            JSONObject()
                .put("deviceModel", android.os.Build.MODEL)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put(
                    "computerAdbConnected",
                    com.helix.app.automation.AutomationModule.computerAdbBackend
                        ?.state() == com.helix.extensions.mobileuse.automation.AutomationBackendState.READY,
                ).put("session", session)
                .put("passed", passed)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("trajectory", JSONObject(evaluationTrajectory(c, session).toString()))
                .put("turnStates", JSONArray(turns.map { it.state }))
                .put(
                    "calls",
                    JSONArray(
                        turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }.map { call ->
                            val result = c.storage.toolResults.byToolCall(call.callId)
                            JSONObject()
                                .put("name", call.name)
                                .put("args", JSONObject(call.argsJson))
                                .put("state", call.state)
                                .put("resultStatus", result?.status)
                                .put("summary", result?.summary)
                                .put("result", result?.let { c.storage.toolResults.readContent(it) })
                        },
                    ),
                )
        File(app.filesDir, "validation/emulator-acceptance/model-journey.json").apply {
            parentFile!!.mkdirs()
            writeText(trace.toString())
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
