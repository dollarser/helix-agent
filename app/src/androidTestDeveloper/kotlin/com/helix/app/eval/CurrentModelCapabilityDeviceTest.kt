package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real-model opt-in. Host prepares and scores owned fixtures; Helix alone chooses and executes task actions. */
class CurrentModelCapabilityDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val args get() = InstrumentationRegistry.getArguments()

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught") // Save failure evidence and restore user configuration.
    fun solveOwnedTask() =
        runBlocking {
            val optIn = args.getString("helixRealModel") ?: "false"
            require(optIn in setOf("true", "false")) { "Invalid helixRealModel value" }
            org.junit.Assume.assumeTrue(
                "Explicit opt-in required: helixRealModel",
                optIn == "true",
            )
            val case = requireNotNull(args.getString("helixCase"))
            require(case in setOf("gui", "files", "recovery", "combined", "keyboard", "notification"))
            val provider = requireNotNull(args.getString("helixProvider"))
            val model = requireNotNull(args.getString("helixModel"))
            val trial = requireNotNull(args.getString("helixTrial"))
            require(trial.matches(Regex("[a-z0-9-]+")))
            val original = c.chatService.screen.value.openSessionId
            val session = c.chatService.createSession("能力评测 $trial/$case", provider, model)
            val binding = requireNotNull(c.storage.workspaces.binding(session))
            val root =
                c.storage.workspaces
                    .managedDirectory(binding.workspaceId)
                    .resolve(binding.relativePath)
                    .toFile()
            val ref = FileScopePath(binding.workspaceId, binding.relativePath).toModelReference()
            val input = File(root, "input/orders.csv").apply { parentFile!!.mkdirs() }
            val source = "城市,金额,状态\n北京,18,完成\n上海,9,完成\n北京,24,完成\n上海,17,取消\n"
            input.writeText(source)
            val output = File(root, "output/result.txt")
            val selection = MobileUseEvaluationSelection(app)
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            var failure: String? = null
            var passed = false
            val started = SystemClock.elapsedRealtime()
            try {
                c.pluginService.setEnabled(plugin.id, true)
                selection.select(session, setOf(app.packageName, "${app.packageName}.test"), case == "notification")
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.chatService.openSession(session)
                c.chatService.setMode(AgentMode.ACT)
                c.chatService.setTurnBudgets(TurnBudgets(40, 30, 262144, 8192, 524288))
                await(10_000) { c.chatService.screen.value.openSessionId == session }
                if (case in setOf("gui", "combined", "keyboard", "notification")) {
                    app.startActivity(
                        Intent()
                            .setComponent(
                                ComponentName(
                                    "${app.packageName}.test",
                                    AutomationEvaluationActivity::class.java.name,
                                ),
                            ).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
                            ).putExtra("modelJourney", true)
                            .putExtra("notificationPrompt", case == "notification"),
                    )
                    SystemClock.sleep(800)
                }
                val prompt = capabilityPrompt(case, ref)
                println("CAPABILITY_SESSION=$session;CASE=$case;MODEL=$model")
                c.chatService.sendTestMessage(session, prompt)
                await(240_000) {
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull()
                        ?.let { TurnState.valueOf(it.state).isTerminal } ==
                        true
                }
                val completed =
                    c.storage.turns
                        .listBySession(session)
                        .last()
                        .state == "COMPLETED"
                val screenDone =
                    if (case in setOf("gui", "combined", "keyboard", "notification")) {
                        fixtureCompletedOnce(case == "notification")
                    } else {
                        true
                    }
                val fileDone = fileCompleted(case, output)
                passed = completed && screenDone && fileDone && input.readText() == source
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
            } finally {
                c.chatService.stop()
                saveEvidence(case, trial, session, model, started, passed, failure)
                selection.close()
                c.pluginService.setEnabled(plugin.id, plugin.enabled)
                if (original != null) c.chatService.openSession(original) else c.chatService.closeSession()
            }
            assertTrue("Task not achieved: $trial/$case; retained session $session", passed)
        }

    private fun saveEvidence(
        case: String,
        trial: String,
        session: String,
        model: String,
        started: Long,
        passed: Boolean,
        failure: String?,
    ) {
        val turn =
            c.storage.turns
                .listBySession(session)
                .lastOrNull()
        val calls = turn?.let { c.storage.toolCalls.listByTurn(it.id) }.orEmpty()
        val result =
            buildJsonObject {
                put("case", case)
                put("trial", trial)
                put("session", session)
                put("model", model)
                put("passed", passed)
                put("failure", failure)
                put("turnState", turn?.state)
                put("elapsedMs", SystemClock.elapsedRealtime() - started)
                put("trajectory", evaluationTrajectory(c, session))
                put(
                    "usage",
                    JsonArray(
                        turn?.let { c.storage.modelCalls.listByTurn(it.id) }.orEmpty().map {
                            kotlinx.serialization.json.JsonPrimitive(it.usage)
                        },
                    ),
                )
                put(
                    "calls",
                    JsonArray(
                        calls.map { call ->
                            buildJsonObject {
                                put("name", call.name)
                                put("state", call.state)
                                put("args", call.argsJson)
                                val result = c.storage.toolResults.byToolCall(call.callId)
                                put("resultStatus", result?.status)
                                put("summary", result?.summary)
                                put("result", result?.let { c.storage.toolResults.readContent(it) })
                            }
                        },
                    ),
                )
                put(
                    "response",
                    c.storage.messages
                        .listBySession(session)
                        .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                        .mapNotNull { c.storage.messages.readContent(it) }
                        .joinToString("\n"),
                )
            }
        File(app.filesDir, "capability-eval")
            .apply { mkdirs() }
            .resolve("$trial-$case.json")
            .writeText(result.toString())
        println("CAPABILITY_RESULT=$trial/$case;PASSED=$passed;FAILURE=$failure")
    }

    private fun fileCompleted(
        case: String,
        output: File,
    ): Boolean =
        when (case) {
            "gui", "keyboard", "notification" -> {
                true
            }

            "combined" -> {
                output.exists() && output.readText().trim() == "TEST_DONE:你好 Helix"
            }

            else -> {
                output.exists() && output.readLines().filter { it.isNotBlank() }.map { it.trim() } ==
                    listOf("北京,42", "上海,9")
            }
        }

    private fun fixtureCompletedOnce(notification: Boolean): Boolean {
        val uri = android.net.Uri.parse("content://${app.packageName}.test.capability-state/result")
        return app.contentResolver.query(uri, null, null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == "TEST_DONE:你好 Helix" && it.getInt(1) == 1 &&
                (!notification || it.getInt(2) == 0)
        } == true
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

private fun capabilityPrompt(
    case: String,
    root: String,
): String {
    val boundary = "这是隔离测试，只允许操作当前测试页面和本会话目录 $root，不操作其他应用或账号。"
    val gui = "把当前页面的“测试输入”填写为“你好 Helix”，向下找到“完成测试”并点击一次，确认实际显示成功结果。"
    return boundary +
        when (case) {
            "notification" -> {
                "请在当前测试应用的通知授权弹窗选择不允许，然后在测试页面填写你好 Helix，滚动找到完成按钮并点击一次，最后核实完成结果。"
            }

            "gui" -> {
                gui
            }

            "keyboard" -> {
                "先点击测试输入框使软键盘出现，再执行以下操作：" + gui
            }

            "combined" -> {
                gui + "然后把页面实际显示的 TEST_DONE 开头的完整结果写入 output/result.txt，不加解释。"
            }

            else -> {
                "读取 input/${if (case == "recovery") "order.csv" else "orders.csv"}，" +
                    "按城市汇总状态为完成的金额，取消订单不计入。按北京、上海顺序将两行“城市,合计”写入 output/result.txt，" +
                    "不要表头和说明，不修改原始数据。若文件名有误，请检查目录找到订单数据后继续。"
            }
        }
}
