package com.helix.app.eval

import android.app.UiAutomation
import android.graphics.Bitmap
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
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.provider.api.CleartextWarning
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Manual owned-emulator pilot for the real-model Mobile Use WeChat download/install journey. */
class MobileUseWechatPilotDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val center get() = AutomationPermissionCenter(app)

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun downloadAndInstallWechatWithRealModel() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val optIn = arguments.getString("helixWechatPilot")
            org.junit.Assume.assumeTrue("Requires an explicit WeChat pilot profile", optIn != null)
            require(optIn == "true" && arguments.getString("helixRealModel") == "true")
            val providerPort = requireNotNull(arguments.getString(ARG_PROVIDER_PORT)).toInt()
            require(providerPort in 1..65535)
            val previous = container.chatService.runControl.value
            val previousProfile = container.profileStore.profile
            val services =
                android.provider.Settings.Secure.getString(
                    app.contentResolver,
                    "enabled_accessibility_services",
                )
            val accessibilityEnabled =
                android.provider.Settings.Secure.getInt(
                    app.contentResolver,
                    "accessibility_enabled",
                    0,
                )
            var provider: String? = null
            var session: String? = null
            var failure: String? = null
            var exposed = kotlinx.serialization.json.JsonObject(emptyMap())
            val started = SystemClock.elapsedRealtime()
            val evidenceFile = File(app.filesDir, EVIDENCE_NAME)
            val screenshotFile = File(app.filesDir, SCREENSHOT_NAME)
            val prompt = arguments.getString(ARG_PROMPT) ?: DEFAULT_PROMPT
            val preserveDownloads = arguments.getString(ARG_PRESERVE_DOWNLOADS).toBoolean()
            val preserveChrome = arguments.getString(ARG_PRESERVE_CHROME).toBoolean()

            try {
                ensureAccessibility(services)
                container.profileStore.switchTo(SafetyProfile.ADVANCED)
                prepareDevice(preserveDownloads, preserveChrome)

                provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Mobile Use WeChat pilot",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://10.0.2.2:$providerPort/v1"),
                            MODEL,
                            "{}",
                            false,
                            CleartextWarning("10.0.2.2", providerPort),
                            emptyList(),
                        ),
                        null,
                    )
                val probe = container.providerService.runConnectionTest(provider)
                check(probe is com.helix.provider.api.ProbeOutcome.Ok) {
                    "Provider connection failed: $probe"
                }
                session = container.chatService.createSession("Mobile Use WeChat pilot", provider, MODEL)
                container.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                com.helix.app.eval
                    .selectMobileUseForTest(session, emptySet(), wholePhone = true)
                container.chatService.openSession(session)
                container.chatService.setMode(AgentMode.ACT)
                container.chatService.setTurnBudgets(
                    TurnBudgetBounds.validate(
                        TurnBudgets(
                            maxSteps = TurnBudgetBounds.DEFAULT.maxSteps,
                            maxModelCalls = TurnBudgetBounds.DEFAULT.maxModelCalls,
                            maxInputTokens = 131_072,
                            maxOutputTokens = TurnBudgetBounds.DEFAULT.maxOutputTokens,
                            maxTotalTokens = TurnBudgetBounds.DEFAULT.maxTotalTokens,
                        ),
                    ),
                )
                awaitMobileUseProjection(session)

                exposed = exposedEvaluationTools(container, AgentMode.ACT)
                check("android.open_uri" in exposed) { "android.open_uri missing from active Mobile Use surface" }
                check("ui.snapshot" in exposed && "tools.search" in exposed) { "Mobile Use discovery is unavailable" }
                check(
                    container.toolPipeline.registry
                        .all()
                        .any { it.name.value == "ui.ime_enter" },
                ) {
                    "ui.ime_enter missing from registered Mobile Use tools"
                }

                container.chatService.sendTestMessage(session, prompt)
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
                val installed = isPackageInstalled(WECHAT_PACKAGE)
                val finalSnapshot =
                    currentSession?.let {
                        runCatching { evaluationAutomationPort(center, it).snapshot() }.getOrNull()
                    }
                val assistantText =
                    currentSession?.let { id ->
                        container.storage.messages
                            .listBySession(id)
                            .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                            .mapNotNull { container.storage.messages.readContent(it) }
                            .joinToString("\n")
                    }
                runCatching {
                    automation.takeScreenshot()?.compress(Bitmap.CompressFormat.PNG, 100, screenshotFile.outputStream())
                }
                val evidence =
                    buildJsonObject {
                        put("prompt", prompt)
                        put("model", MODEL)
                        put("toolVersions", exposed)
                        put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        put("failure", failure)
                        put("wechatInstalled", installed)
                        put("turnId", turn?.id)
                        put("turnState", turn?.state)
                        put("turnErrorCode", turn?.errorCode)
                        put("stepCount", turn?.stepCount)
                        put("finalSnapshotStatus", finalSnapshot?.status?.name)
                        put("finalPackage", finalSnapshot?.snapshot?.packageName ?: finalSnapshot?.targetPackage)
                        put("assistantText", assistantText)
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
                        put("usedOpenUri", calls.any { it.name == "android.open_uri" })
                        put("usedOpenSettings", calls.any { it.name == "android.open_settings" })
                        put("usedImeEnter", calls.any { it.name == "ui.ime_enter" })
                        put(
                            "usedSetTextSubmit",
                            calls.any { it.name == "ui.set_text" && it.argsJson.contains("\"submit\":true") },
                        )
                        put(
                            "registeredUiTools",
                            JsonArray(
                                container.toolPipeline.registry
                                    .all()
                                    .filter { it.name.value.startsWith("ui.") }
                                    .map { JsonPrimitive(it.name.value) },
                            ),
                        )
                    }
                evidenceFile.writeText(evidence.toString())

                container.chatService.stop()
                container.chatService.closeSession()
                container.chatService.setMode(previous.mode)
                container.chatService.setTurnBudgets(previous.budgets)
                provider?.let { container.providerService.delete(it) }
                session?.let(::deselectMobileUseForTest)
                restoreAccessibility(services, accessibilityEnabled)
                container.profileStore.switchTo(previousProfile)

                check(failure == null) { requireNotNull(failure) }
                assertTrue("WeChat package was not installed; evidence=$evidenceFile", installed)
            }
        }

    private fun prepareDevice(
        preserveDownloads: Boolean,
        preserveChrome: Boolean,
    ) {
        shell("pm uninstall $WECHAT_PACKAGE")
        shell("am force-stop com.google.android.packageinstaller")
        if (!preserveDownloads) shell("rm -f /sdcard/Download/weixin*.apk /sdcard/Download/WeChat*.apk")
        if (!preserveChrome) shell("pm clear com.android.chrome")
        if (preserveDownloads) {
            shell(
                "am start -n com.android.chrome/" +
                    "org.chromium.chrome.browser.app.download.home.DownloadActivity",
            )
        } else {
            shell("input keyevent KEYCODE_HOME")
        }
        Thread.sleep(500)
    }

    private fun ensureAccessibility(previousServices: String?) {
        val component = "${app.packageName}/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
        val previous = previousServices.orEmpty().split(':').filter(String::isNotBlank)
        val withoutHelix = previous.filterNot { it == component }
        if (withoutHelix.isEmpty()) {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
        } else {
            shell("settings put secure enabled_accessibility_services ${withoutHelix.joinToString(":")}")
        }
        Thread.sleep(500)
        val allServices = (withoutHelix + component).distinct()
        shell("settings put secure enabled_accessibility_services ${allServices.joinToString(":")}")
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
        val deadline = SystemClock.elapsedRealtime() + 420_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null && TurnState.valueOf(turn.state).isTerminal) return
            Thread.sleep(250)
        }
        error("Mobile Use WeChat pilot exceeded 420 seconds")
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getApplicationInfo(packageName, 0)
        }.isSuccess

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

    companion object {
        private const val ARG_PROMPT = "helixPilotPrompt"
        private const val ARG_PRESERVE_DOWNLOADS = "helixPilotPreserveDownloads"
        private const val ARG_PRESERVE_CHROME = "helixPilotPreserveChrome"
        private const val ARG_PROVIDER_PORT = "helixPilotProviderPort"
        private const val DEFAULT_PROMPT =
            "Install the already downloaded WeChat APK from Chrome Downloads. Do not redownload or switch stores. " +
                "If Android says this source is not allowed to install apps, use the installer-provided Settings " +
                "control when available, return, and continue installing the same APK until WeChat is installed."
        private const val MODEL = "Qwen3.8-27B"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val EVIDENCE_NAME = "mobile-use-wechat-pilot.json"
        private const val SCREENSHOT_NAME = "mobile-use-wechat-final.png"
    }
}
