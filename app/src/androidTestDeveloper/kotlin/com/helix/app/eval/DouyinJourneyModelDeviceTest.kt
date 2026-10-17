package com.helix.app.eval

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Owner-opted real-app journey. The observer never downloads, installs, clicks or uninstalls. */
class DouyinJourneyModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test
    // Keep owned setup, original Turn identities and restoration in one lifecycle.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun autonomousInstallWatchAndUninstall() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("helixDouyinJourney") == "true")
            val port = requireNotNull(args.getString("pluginSkillPort")).toInt()
            val model = requireNotNull(args.getString("pluginSkillModel"))
            val requestedBackend = args.getString("pluginSkillBackend") ?: "accessibility"
            require(requestedBackend in setOf("accessibility", "shizuku"))
            val runDeadline = args.getString("helixJourneyDeadlineEpoch")?.toLong()
            check(runDeadline == null || System.currentTimeMillis() < runDeadline)
            check(!installed()) { "Requires an owned test instance without Douyin" }
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        "generic-openai",
                        "Owned Douyin journey",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                        CleartextWarning("127.0.0.1", port),
                        emptyList(),
                    ),
                    null,
                )
            val profile = c.profileStore.profile
            val controls = c.runControlStore.current
            var createdSession: String? = null
            val selection = MobileUseEvaluationSelection(app)
            val backend = OwnedAccessibilityBackend(app)
            val evidence = File(app.filesDir, "douyin-journey").apply { mkdirs() }
            val requested = mutableListOf<String>()
            var sawInstalled = false
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val session = c.chatService.createSession("抖音自主完整旅程", provider, model)
                createdSession = session
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                c.firstLaunch.markSeen()
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (requestedBackend == "accessibility") backend.connect()
                if (requestedBackend == "shizuku") {
                    // Instrumentation may start without the normal provider-process binder handoff.
                    rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(app)
                    val connectedBy = SystemClock.elapsedRealtime() + 30_000
                    while (!com.helix.tools.deviceaccess.DeviceAccess
                            .shizukuGranted() &&
                        SystemClock.elapsedRealtime() < connectedBy
                    ) {
                        delay(100)
                    }
                    check(
                        com.helix.tools.deviceaccess.DeviceAccess
                            .shizukuGranted(),
                    )
                }
                run {
                    check(selection.select(session, emptySet(), true))
                    c.sessionPermissionEdit.saveSessionConfig(
                        session,
                        SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                        System.currentTimeMillis(),
                    )
                    c.chatService.openTestSessionControl(
                        session,
                        AgentMode.ACT,
                        TurnBudgets(maxOutputTokens = 8192),
                    )
                    val prompts =
                        listOf(
                            "请自主从官方来源下载并安装抖音，然后打开，实际观看一个公开视频至少10秒，最后卸载抖音并核实。" +
                                "已核实的官方入口是 https://m.douyin.com/app_download ，请观察页面并使用真实下载入口，" +
                                "不要猜测下载地址，也不要把网页打开当作安装完成。允许操作浏览器和系统安装、卸载页面。" +
                                "允许完成观看必要的普通启动同意；不要登录、注册、使用任何已有账号，" +
                                "不要点赞、评论、关注、分享、付款，也不要授予无关权限。" +
                                "若必须登录才可观看，直接卸载并说明实际登录阻挡。不要操作其他用户数据。" +
                                "所有下载、安装、观看和卸载必须由你实际调用工具完成，最后说明观察到的结果。" +
                                if (requestedBackend == "shizuku") {
                                    "本实例的Shizuku已启动并授权，无需打开其管理App。" +
                                        "官方下载页在Chrome已验证可交互；本次优先通过android.open_uri和手机界面操作，" +
                                        "不必逆向或反复读取网页JavaScript。实际操作仍由你自主选择工具并完成。"
                                } else {
                                    ""
                                },
                            "这是唯一一次人工上下文补充：请先核查实际状态，再继续尚未完成的下载、安装、观看和卸载。" +
                                "不要把操作意图文字当作完成。若被登录或无法解决的环境问题阻挡，" +
                                "请如实说明，已安装则尝试卸载并核实；保持不登录、不操作账号的限制。",
                        )
                    for ((index, prompt) in prompts.withIndex()) {
                        check(runDeadline == null || System.currentTimeMillis() < runDeadline) {
                            "Authorized model-test window ended"
                        }
                        val previous =
                            c.storage.turns
                                .listBySession(session)
                                .map { it.id }
                                .toSet()
                        c.chatService.sendTestMessage(session, prompt)
                        val admitted = SystemClock.elapsedRealtime() + 15_000
                        while (c.storage.turns
                                .listBySession(session)
                                .none { it.id !in previous } &&
                            SystemClock.elapsedRealtime() < admitted
                        ) {
                            delay(100)
                        }
                        val id =
                            c.storage.turns
                                .listBySession(session)
                                .first { it.id !in previous }
                                .id
                        requested.add(id)
                        println("DOUYIN_REQUEST attempt=$index session=$session turn=$id")
                        val remaining = runDeadline?.let { it - System.currentTimeMillis() } ?: 900_000
                        val deadline = SystemClock.elapsedRealtime() + minOf(900_000, remaining)
                        while (true) {
                            sawInstalled = sawInstalled || installed()
                            val turn = requireNotNull(c.storage.turns.find(id))
                            if (TurnState.valueOf(turn.state).isTerminal) break
                            check(SystemClock.elapsedRealtime() < deadline) { "Original Turn exceeded 15 minutes: $id" }
                            delay(200)
                        }
                        val turn = requireNotNull(c.storage.turns.find(id))
                        saveTrajectory(evidence.resolve("attempt-$index-trajectory.json"), session)
                        println(
                            "DOUYIN_RESULT attempt=$index turn=$id state=${turn.state} error=${turn.errorCode} " +
                                "sawInstalled=$sawInstalled installedNow=${installed()}",
                        )
                        if ((sawInstalled && !installed()) || turn.state != TurnState.COMPLETED.name) break
                    }
                    // Playback or a genuine mandatory-login branch needs separate passive evidence review.
                    // This assertion proves only observed installation followed by absence, never video playback.
                    assertTrue("No observed installation; inspect retained session $session", sawInstalled)
                    assertTrue("Douyin remains installed; model did not finish cleanup", !installed())
                    assertTrue(
                        "Expected actual mobile tool execution",
                        requested
                            .flatMap { c.storage.toolCalls.listByTurn(it) }
                            .any { it.name.startsWith("ui.") && it.state == "COMPLETED" },
                    )
                    if (requestedBackend == "shizuku") {
                        assertTrue(
                            "No actual Shizuku operation result",
                            requested
                                .flatMap {
                                    c.storage.toolCalls.listByTurn(it)
                                }.any { call ->
                                    c.storage.toolResults
                                        .byToolCall(call.id)
                                        ?.let(c.storage.toolResults::readContent)
                                        ?.contains("\"backend\":\"shizuku\"") == true
                                },
                        )
                    }
                    println("DOUYIN_PACKAGE_SEQUENCE=PASS;PLAYBACK_REVIEW=pending;SESSION=$session")
                }
            } finally {
                createdSession?.let { session ->
                    saveTrajectory(evidence.resolve("final-trajectory.json"), session)
                    c.storage.turns.listBySession(session).asReversed().forEach {
                        c.chatService.stopTurn(it.id)
                        withTimeout(20_000) { c.agentRuntime.observe(TurnId(it.id)).collect {} }
                    }
                    saveTrajectory(evidence.resolve("cleanup-trajectory.json"), session)
                }
                selection.close()
                backend.close()
                c.chatService.closeSession()
                c.profileStore.switchTo(profile)
                c.runControlStore.setMode(controls.mode)
                c.runControlStore.setReasoning(controls.reasoning)
                c.runControlStore.setBudgets(controls.budgets)
                c.providerService.delete(provider)
                // Keep the synthetic session and trajectory until the owned VM evidence is exported.
            }
        }

    private fun saveTrajectory(
        file: File,
        session: String,
    ) {
        val turns = c.storage.turns.listBySession(session)
        val calls = turns.flatMap { c.storage.toolCalls.listByTurn(it.id) }
        val data =
            buildJsonObject {
                put("summary", evaluationTrajectory(c, session))
                put(
                    "turns",
                    JsonArray(
                        turns.map {
                            buildJsonObject {
                                put("id", JsonPrimitive(it.id))
                                put("state", JsonPrimitive(it.state))
                                put("error", JsonPrimitive(it.errorCode))
                            }
                        },
                    ),
                )
                put(
                    "calls",
                    JsonArray(
                        calls.map {
                            buildJsonObject {
                                put("name", JsonPrimitive(it.name))
                                put("state", JsonPrimitive(it.state))
                                put("args", JsonPrimitive(it.argsJson))
                                put(
                                    "result",
                                    JsonPrimitive(
                                        c.storage.toolResults
                                            .byToolCall(it.id)
                                            ?.let(c.storage.toolResults::readContent),
                                    ),
                                )
                            }
                        },
                    ),
                )
                put(
                    "messages",
                    JsonArray(
                        c.storage.messages.listBySession(session).map {
                            buildJsonObject {
                                put("role", JsonPrimitive(it.role))
                                put("kind", JsonPrimitive(it.kind))
                                put("content", JsonPrimitive(c.storage.messages.readContent(it)))
                            }
                        },
                    ),
                )
            }
        file.writeText(data.toString())
    }

    private fun installed(): Boolean =
        try {
            app.packageManager.getPackageInfo("com.ss.android.ugc.aweme", 0)
            true
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
}
