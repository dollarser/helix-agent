package com.helix.app.eval

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.CleartextWarning
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real-model recovery regression for Android's per-source unknown-app installation gate. */
class MobileUseUnknownSourcesPilotDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val center get() = AutomationPermissionCenter(app)

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun recoverChromeUnknownAppSourceWithRealModel() =
        runBlocking {
            val previousControl = container.chatService.runControl.value
            val previousProfile = container.profileStore.profile
            val previousServices =
                android.provider.Settings.Secure.getString(
                    app.contentResolver,
                    "enabled_accessibility_services",
                )
            val previousAccessibility =
                android.provider.Settings.Secure.getInt(
                    app.contentResolver,
                    "accessibility_enabled",
                    0,
                )
            val previousInstallOp = shellText("appops get $CHROME_PACKAGE REQUEST_INSTALL_PACKAGES")
            var provider: String? = null
            var session: String? = null
            var failure: String? = null
            val evidenceFile = File(app.filesDir, EVIDENCE_NAME)

            try {
                ensureAccessibility(previousServices)
                container.profileStore.switchTo(SafetyProfile.ADVANCED)
                shell("appops set $CHROME_PACKAGE REQUEST_INSTALL_PACKAGES deny")
                shell("input keyevent KEYCODE_HOME")

                provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Unknown source recovery pilot",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://10.0.2.2:30008/v1"),
                            MODEL,
                            "{}",
                            false,
                            CleartextWarning("10.0.2.2", 30008),
                            emptyList(),
                        ),
                        null,
                    )
                check(container.providerService.runConnectionTest(provider) is com.helix.provider.api.ProbeOutcome.Ok)

                session = container.chatService.createSession("Unknown source recovery pilot", provider, MODEL)
                container.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                center.authorizeConversation(session, emptySet(), wholePhone = true)
                container.chatService.openSession(session)
                container.chatService.setMode(AgentMode.ACT)
                container.chatService.setTurnBudgets(
                    TurnBudgetBounds.validate(
                        TurnBudgets(
                            maxSteps = TurnBudgetBounds.DEFAULT.maxSteps,
                            maxModelCalls = TurnBudgetBounds.DEFAULT.maxModelCalls,
                            maxInputTokens = 131_072,
                            maxOutputTokens = 4_096,
                            maxTotalTokens = TurnBudgetBounds.DEFAULT.maxTotalTokens,
                        ),
                    ),
                )
                awaitMobileUseProjection(session)

                container.chatService.sendTestMessage(
                    session,
                    "一个经过验证的官方 APK 已由 Chrome 下载并打开，但 Android 阻止继续安装，" +
                        "因为当前来源 Chrome 没有获准安装未知应用。请处理这个系统权限阻塞，" +
                        "不要更换下载源，也不要改用 Google Play。Chrome 包名是 com.android.chrome。" +
                        "请让这个来源获得安装权限，并在完成后验证权限已经开启。",
                )
                awaitTerminal(session)
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
            } finally {
                val currentSession = session
                val turn =
                    currentSession?.let {
                        container.storage.turns
                            .listBySession(it)
                            .lastOrNull()
                    }
                val calls = turn?.let { container.storage.toolCalls.listByTurn(it.id) }.orEmpty()
                val appOp = shellText("appops get $CHROME_PACKAGE REQUEST_INSTALL_PACKAGES")
                val allowed = appOp.contains("allow", ignoreCase = true)
                val assistantText =
                    currentSession?.let { id ->
                        container.storage.messages
                            .listBySession(id)
                            .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                            .mapNotNull { container.storage.messages.readContent(it) }
                            .joinToString("\n")
                    }
                evidenceFile.writeText(
                    buildJsonObject {
                        put("failure", failure)
                        put("turnId", turn?.id)
                        put("turnState", turn?.state)
                        put("turnErrorCode", turn?.errorCode)
                        put("stepCount", turn?.stepCount)
                        put("chromeInstallAppOp", appOp)
                        put("permissionAllowed", allowed)
                        put("assistantText", assistantText)
                        put("usedOpenSettings", calls.any { it.name == "android.open_settings" })
                        put(
                            "openedGooglePlay",
                            calls.any {
                                it.name == "android.open_uri" && it.argsJson.contains("play.google.com")
                            },
                        )
                        put(
                            "calls",
                            JsonArray(
                                calls.map { call ->
                                    val result = container.storage.toolResults.byToolCall(call.callId)
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("args", call.argsJson)
                                        put("state", call.state)
                                        put("resultStatus", result?.status)
                                        put("resultSummary", result?.summary)
                                        put(
                                            "result",
                                            result?.let { container.storage.toolResults.readContent(it) },
                                        )
                                    }
                                },
                            ),
                        )
                    }.toString(),
                )

                container.chatService.stop()
                container.chatService.closeSession()
                container.chatService.setMode(previousControl.mode)
                container.chatService.setTurnBudgets(previousControl.budgets)
                provider?.let { container.providerService.delete(it) }
                session?.let(center::revokeConversation)
                restoreInstallOp(previousInstallOp)
                restoreAccessibility(previousServices, previousAccessibility)
                container.profileStore.switchTo(previousProfile)

                check(failure == null) { requireNotNull(failure) }
                assertTrue("Chrome unknown-app-source permission was not enabled; evidence=$evidenceFile", allowed)
                assertTrue(
                    "Model never used android.open_settings; evidence=$evidenceFile",
                    calls.any { it.name == "android.open_settings" },
                )
                assertFalse(
                    "Model incorrectly fell back to Google Play; evidence=$evidenceFile",
                    calls.any { it.name == "android.open_uri" && it.argsJson.contains("play.google.com") },
                )
            }
        }

    private fun ensureAccessibility(previousServices: String?) {
        val component = "${app.packageName}/com.helix.tools.automation.HelixAccessibilityService"
        val previous = previousServices.orEmpty().split(':').filter(String::isNotBlank)
        val withoutHelix = previous.filterNot { it == component }
        if (withoutHelix.isEmpty()) {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
        } else {
            shell("settings put secure enabled_accessibility_services ${withoutHelix.joinToString(":")}")
        }
        Thread.sleep(500)
        shell(
            "settings put secure enabled_accessibility_services ${(withoutHelix + component).distinct().joinToString(
                ":",
            )}",
        )
        shell("settings put secure accessibility_enabled 1")
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (center.serviceState() != AutomationServiceState.CONNECTED) {
            check(SystemClock.elapsedRealtime() < deadline) { "Accessibility did not connect" }
            Thread.sleep(100)
        }
    }

    private fun awaitMobileUseProjection(session: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (
            container.chatService.screen.value.openSessionId != session ||
            com.helix.app.automation.AutomationModule
                .scopeFor("ui.snapshot", session) == null
        ) {
            check(SystemClock.elapsedRealtime() < deadline) { "Mobile Use projection did not converge" }
            Thread.sleep(50)
        }
    }

    private fun awaitTerminal(session: String) {
        val deadline = SystemClock.elapsedRealtime() + 180_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null && TurnState.valueOf(turn.state).isTerminal) return
            Thread.sleep(200)
        }
        error("Unknown-source recovery pilot exceeded 180 seconds")
    }

    private fun restoreInstallOp(previous: String) {
        val mode =
            when {
                previous.contains("allow", ignoreCase = true) -> "allow"
                previous.contains("default", ignoreCase = true) -> "default"
                else -> "deny"
            }
        shell("appops set $CHROME_PACKAGE REQUEST_INSTALL_PACKAGES $mode")
    }

    private fun restoreAccessibility(
        previousServices: String?,
        previousEnabled: Int,
    ) {
        if (previousServices.isNullOrBlank()) {
            shell("settings delete secure enabled_accessibility_services")
        } else {
            shell("settings put secure enabled_accessibility_services $previousServices")
        }
        shell("settings put secure accessibility_enabled $previousEnabled")
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun shellText(command: String): String =
        automation.executeShellCommand(command).use { descriptor ->
            java.io
                .FileInputStream(descriptor.fileDescriptor)
                .bufferedReader()
                .use { it.readText() }
        }

    companion object {
        private const val MODEL = "Qwen3.8-27B"
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val EVIDENCE_NAME = "mobile-use-unknown-sources-pilot.json"
    }
}
