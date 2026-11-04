package com.helix.app.eval

import android.content.ComponentName
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real configured model; numeric ground truth is visible UI, never included in the prompt. */
class MobileUseNumberReadbackDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val fixtureComponent get() =
        ComponentName("${app.packageName}.test", BackgroundDisplayFixtureActivity::class.java.name)

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught") // One case preserves scope and evidence in finally.
    fun modelReportsObservedNumbersAndUnitsWithoutInventingExtraFigures() {
        runBlocking(Dispatchers.IO) {
            val args = InstrumentationRegistry.getArguments()
            val optIn = args.getString("helixRealNumbers")
            org.junit.Assume.assumeTrue("Requires explicit real-model numeric inspection", optIn != null)
            require(optIn == "true")
            val trial = requireNotNull(args.getString("helixTrial"))
            require(trial.matches(Regex("[a-z0-9-]+")))
            app.runtimeRecoveryReady.await()
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "Owner task active; do not disturb" }
            val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
            val oldScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
            val oldDisplay = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
            val profile = c.profileStore.profile
            val original = c.chatService.screen.value.openSessionId
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            val session =
                c.chatService.createSession(
                    "数字原文复验 $trial",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val started = SystemClock.elapsedRealtime()
            var passed = false
            var failure: String? = null
            try {
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
                store.configureGlobal(setOf("${app.packageName}.test"), false)
                store.configureExecution(true, true)
                c.pluginService.setEnabled(plugin.id, true)
                c.pluginService.catalog.select(session, plugin.id, true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.chatService.openTestSessionControl(session, AgentMode.ACT, c.chatService.runControl.value.budgets)
                app.startActivity(
                    Intent()
                        .setComponent(
                            fixtureComponent,
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("numericReadback", true),
                )
                report("NUMBER_READBACK_SESSION=$session;TRIAL=$trial")
                val prompt =
                    "请使用手机操作，在已选后台屏打开测试应用 com.helix.agent.developer.test，" +
                        "获取公开数据校验页的截图，再读取页面的下载、评论、评分。" +
                        "最终只回答这三个字段，每个字段一行，保留原文数字和单位，不换算、不估计。" +
                        "不要写日期、其他指标或未请求字段，即使是说明它未被请求也不要复述。" +
                        "只操作此测试应用，不操作主屏或其他应用，不使用终端、文件或OCR，完成后停留在测试页面。"
                val submission = ChatSubmission(session, 0, "number-$trial", prompt)
                val admission = c.chatService.sendSubmission(submission).await()
                check(admission.outcome is ChatSubmissionOutcome.Accepted)
                awaitTerminal(session)
                verifyReadback(session)
                passed = true
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
                throw error
            } finally {
                withContext(NonCancellable) {
                    val turn =
                        c.storage.turns
                            .listBySession(session)
                            .lastOrNull()
                    if (turn != null && !TurnState.valueOf(turn.state).isTerminal) {
                        c.chatService.stopTurn(turn.id)
                        while (c.storage.turns
                                .resolve(turn.id)
                                .state == TurnState.CANCELLING.name
                        ) {
                            delay(100)
                        }
                    }
                    saveClosedLoopEvidence(
                        app,
                        c,
                        ClosedLoopCaseEvidence(
                            session,
                            "number-readback",
                            trial,
                            BuiltInModel.MODEL_ID,
                            started,
                            passed,
                            failure,
                        ),
                    )
                    lines.setLines(MobileUseGrantStore.CONFIG_KEY, oldScope)
                    lines.setLines(MobileUseGrantStore.EXECUTION_KEY, oldDisplay)
                    c.pluginService.catalog.select(session, plugin.id, false)
                    c.pluginService.setEnabled(plugin.id, plugin.enabled)
                    c.profileStore.switchTo(profile)
                    app.startActivity(
                        Intent()
                            .setComponent(
                                fixtureComponent,
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra("numericReadback", false),
                    )
                    original?.let { c.chatService.openSession(it) }
                }
            }
        }
    }

    private suspend fun awaitTerminal(session: String) {
        var previous = ""
        while (true) {
            c.chatService.toolVisionConsent.pending.value.filter { it.sessionId == session }.forEach {
                c.chatService.toolVisionConsent.respond(it.id, true)
            }
            val turn =
                c.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                val progress = "${turn.state};models=${c.storage.modelCalls.listByTurn(turn.id).size}"
                if (previous != progress) {
                    report("NUMBER_PROGRESS=$progress")
                    previous = progress
                }
                if (TurnState.valueOf(turn.state).isTerminal) return
            }
            delay(250)
        }
    }

    private fun report(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply { putString("stream", "$value\n") },
        )
    }

    private fun verifyReadback(session: String) {
        val turn =
            c.storage.turns
                .listBySession(session)
                .last()
        assertEquals(TurnState.COMPLETED.name, turn.state)
        val calls = c.storage.toolCalls.listByTurn(turn.id)
        assertTrue(calls.any { it.name in setOf("ui.screenshot", "ui.snapshot") && it.state == "COMPLETED" })
        val imageConsumed =
            c.storage.modelCalls.listByTurn(turn.id).any { model ->
                c.storage.auditEvents.listByCorrelation(model.id).any { event ->
                    event.type == "budget.request" && Json
                        .parseToJsonElement(event.redactedPayload)
                        .jsonObject["images"]
                        ?.jsonPrimitive
                        ?.intOrNull
                        ?.let { it > 0 } == true
                }
            }
        assertTrue("A captured image must reach a real model request", imageConsumed)
        val answer =
            c.storage.messages
                .listBySession(session)
                .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                .lastOrNull()
                ?.let { c.storage.messages.readContent(it) }
                .orEmpty()
                .replace("**", "")
                .replace("`", "")
                .replace(Regex("""(?m)^\s*\d+[.)、]"""), "")
                .replace(Regex("""3\s*[项个]"""), "")
        for ((label, value) in listOf("下载" to "12.3万次", "评论" to "47条", "评分" to "4.8分")) {
            val normalized = answer.replace(Regex("""\s"""), "")
            assertTrue(
                "Missing observed metric $label=$value; inspect retained answer",
                Regex("$label[^0-9]{0,20}${Regex.escape(value)}").containsMatchIn(normalized),
            )
        }
        assertEquals(setOf("12.3", "47", "4.8"), Regex("""\d+(?:\.\d+)?""").findAll(answer).map { it.value }.toSet())
    }
}
