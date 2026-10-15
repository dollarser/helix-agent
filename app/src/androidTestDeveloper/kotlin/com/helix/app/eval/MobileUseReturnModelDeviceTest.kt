package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.sendTestMessage
import com.helix.app.ui.dismissFirstLaunchIfNeeded
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Owner-opt-in real model; synthetic UI only, no manual navigation after submitting the task. */
class MobileUseReturnModelDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun completedTaskReturnsToOriginatingConversation() = journey(stay = false)

    @Test fun explicitStayKeepsVerifiedTargetVisible() = journey(stay = true)

    @Suppress("LongMethod") // One bounded setup/task/assertion/restoration lifecycle.
    private fun journey(stay: Boolean) =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("helixRealModel") == "true")
            val provider = requireNotNull(args.getString("helixProvider"))
            val model = requireNotNull(args.getString("helixModel"))
            val requireScreenshot = args.getString("helixRequireScreenshot") == "true"
            compose.dismissFirstLaunchIfNeeded()
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            require(
                c.storage.providerConfigs
                    .resolve(provider)
                    .model == model,
            )
            val original = c.chatService.screen.value.openSessionId
            val profile = c.profileStore.profile
            val selection = MobileUseEvaluationSelection(app)
            val title = "Return-${UUID.randomUUID().toString().take(8)}"
            val session = c.chatService.createSession(title, provider, model)
            try {
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                selection.select(session, setOf(app.packageName, "${app.packageName}.test"), false)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.chatService.openSession(session)
                c.chatService.setMode(AgentMode.ACT)
                c.chatService.setTurnBudgets(TurnBudgets(24, 24, 65536, 2048, 100000))
                compose.waitUntil(10_000) { c.chatService.screen.value.openSessionId == session }
                compose.onNodeWithTag("chat-title").assertIsDisplayed().assertTextContains(title)
                app.startActivity(
                    Intent()
                        .setComponent(
                            ComponentName("${app.packageName}.test", AutomationEvaluationActivity::class.java.name),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("modelJourney", true),
                )
                SystemClock.sleep(800)
                val prompt =
                    "这是合成测试，只允许操作当前测试页面及发起本轮的 Helix（${app.packageName}），" +
                        "当前会话标题为 $title，不操作其他应用或账号。" +
                        "把“测试输入”填写为“你好 Helix”，向下找到“完成测试”并点击一次，核实实际成功结果。" +
                        (if (requireScreenshot) "操作前先使用手机截图确认页面，读取实际图像后再操作。" else "") +
                        if (stay) "完成后请停留在测试结果页面，不要返回 Helix。" else ""
                c.chatService.sendTestMessage(session, prompt)
                await(240_000) {
                    c.storage.turns.listBySession(session).lastOrNull()?.let {
                        TurnState.valueOf(it.state).isTerminal
                    } == true
                }
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .last()
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                val models = c.storage.modelCalls.listByTurn(turn.id)
                report(
                    "RETURN_MODEL stay=$stay state=${turn.state} error=${turn.errorCode} " +
                        "originHasWindowFocus=${originHasWindowFocus()}",
                )
                report("RETURN_MODEL calls=${calls.map { it.name to it.state }} usage=${models.map { it.usage }}")
                calls.forEach { call ->
                    report("RETURN_TOOL ${call.name} result=${c.storage.toolResults.byToolCall(call.callId)?.summary}")
                }
                c.storage.messages
                    .listBySession(session)
                    .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                    .forEach { report("RETURN_REPLY ${c.storage.messages.readContent(it)}") }
                assertEquals(turn.errorCode, "COMPLETED", turn.state)
                if (requireScreenshot) {
                    assertTrue("Frame observation missing", calls.any { it.name == "ui.device" })
                    val captures = calls.filter { it.name == "ui.screenshot" && it.state == "COMPLETED" }
                    assertTrue("Successful screenshot missing", captures.isNotEmpty())
                    assertTrue(
                        "Screenshot pixels were not attached for the model",
                        captures.any {
                            c.storage.toolResults.byToolCall(it.callId)?.let { result ->
                                c.storage.toolResults
                                    .readContent(result)
                                    ?.contains("\"pixelsAttached\":true")
                            } == true
                        },
                    )
                }
                val uri = android.net.Uri.parse("content://${app.packageName}.test.capability-state/result")
                app.contentResolver.query(uri, null, null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("TEST_DONE:你好 Helix", it.getString(0))
                    assertEquals("Complete exactly once", 1, it.getInt(1))
                }
                assertEquals("Originating activity focus", !stay, originHasWindowFocus())
                if (!stay) compose.onNodeWithTag("chat-title").assertIsDisplayed().assertTextContains(title)
                assertEquals(session, c.chatService.screen.value.openSessionId)
            } finally {
                try {
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull()
                        ?.let { c.chatService.stopTask(it.id) }
                } finally {
                    selection.close()
                    c.profileStore.switchTo(profile)
                    c.storage.sessions.archive(session, System.currentTimeMillis())
                    if (original != null) c.chatService.openSession(original) else c.chatService.closeSession()
                }
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

    private fun originHasWindowFocus(): Boolean {
        var focused = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { focused = compose.activity.hasWindowFocus() }
        return focused
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply { putString("stream", "$message\n") },
        )
    }
}
