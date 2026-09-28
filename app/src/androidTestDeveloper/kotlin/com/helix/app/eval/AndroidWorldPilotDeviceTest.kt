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
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextAuthorization
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import com.helix.tools.automation.AutomationSessionStartStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in, owned-emulator pilot. Host initializes/scores; only the real Agent performs task actions. */
class AndroidWorldPilotDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val center get() = AutomationPermissionCenter(app)

    @Test
    // Persist any fixture failure, restore state, then fail the test.
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun executeOfficialGoal() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("helix.androidWorld") == "true")
            val target = requireNotNull(arguments.getString("brightness"))
            require(target in setOf("min", "max"))
            val launcher = requireNotNull(arguments.getString("launcher"))
            require(launcher.matches(Regex("[a-zA-Z0-9_.]+")))
            val previous = container.chatService.runControl.value
            val profile = container.profileStore.profile
            val allowlist = center.allowlistedPackages()
            val services =
                android.provider.Settings.Secure
                    .getString(app.contentResolver, "enabled_accessibility_services")
            val enabled =
                android.provider.Settings.Secure
                    .getInt(app.contentResolver, "accessibility_enabled", 0)
            var provider: String? = null
            var session: String? = null
            var failure: String? = null
            var exposed = JsonObject(emptyMap())
            val started = SystemClock.elapsedRealtime()
            try {
                val component = "${app.packageName}/com.helix.tools.automation.HelixAccessibilityService"
                val allServices = (services.orEmpty().split(':').filter(String::isNotBlank) + component).distinct()
                shell("settings put secure enabled_accessibility_services ${allServices.joinToString(":")}")
                shell("settings put secure accessibility_enabled 1")
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (center.serviceState() != AutomationServiceState.CONNECTED) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Accessibility did not connect" }
                    Thread.sleep(100)
                }
                container.profileStore.switchTo(SafetyProfile.ADVANCED)
                val packages = setOf(app.packageName, launcher, "com.android.settings", "com.android.systemui")
                center.replaceAllowlist(packages)
                require(center.startSession(packages).status == AutomationSessionStartStatus.STARTED)
                provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "AndroidWorld pilot",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://10.0.2.2:30008/v1"),
                            "Qwen3.8-27B",
                            "{}",
                            false,
                            CleartextAuthorization("10.0.2.2", 30008),
                            emptyList(),
                        ),
                        null,
                        cleartextConfirmed = true,
                    )
                val probe = container.providerService.runConnectionTest(provider)
                check(probe is com.helix.provider.api.ProbeOutcome.Ok) { "Provider connection failed: $probe" }
                session = container.chatService.createSession("AndroidWorld-$target", provider, "Qwen3.8-27B")
                container.chatService.openSession(session)
                container.chatService.setMode(AgentMode.ACT)
                container.chatService.setTurnBudgets(
                    com.helix.app.runcontrol.TurnBudgetBounds.validate(
                        TurnBudgets(
                            maxSteps = 32,
                            maxModelCalls = 32,
                            maxInputTokens = 131072,
                            maxOutputTokens = 4096,
                            maxTotalTokens = com.helix.app.runcontrol.TurnBudgetBounds.MAX_TOTAL_TOKENS,
                        ),
                    ),
                )
                exposed = exposedEvaluationTools(container, AgentMode.ACT)
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(500)
                container.chatService.sendTestMessage(session, "Turn brightness to the $target value.")
                awaitTerminal(session)
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
            } finally {
                // Capture durable facts even when admission failed and there is no Turn.
                val turn =
                    session?.let {
                        container.storage.turns
                            .listBySession(it)
                            .lastOrNull()
                    }
                val calls = turn?.let { container.storage.toolCalls.listByTurn(it.id) }.orEmpty()
                val evidence =
                    buildJsonObject {
                        put("toolVersions", exposed)
                        put(
                            "registeredUiTools",
                            JsonArray(
                                container.toolPipeline.registry
                                    .all()
                                    .filter { it.name.value.startsWith("ui.") }
                                    .map { JsonPrimitive(it.name.value) },
                            ),
                        )
                        put("target", target)
                        put("goal", "Turn brightness to the $target value.")
                        put("failure", failure)
                        put("turnState", turn?.state)
                        put("errorCode", turn?.errorCode)
                        put("elapsedMs", SystemClock.elapsedRealtime() - started)
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
                                        put("result", result?.let { container.storage.toolResults.readContent(it) })
                                    }
                                },
                            ),
                        )
                        put(
                            "text",
                            session?.let { id ->
                                container.storage.messages
                                    .listBySession(id)
                                    .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                                    .mapNotNull { container.storage.messages.readContent(it) }
                                    .joinToString("\n")
                            },
                        )
                    }
                File(app.filesDir, "androidworld-$target.json").writeText(evidence.toString())
                container.chatService.stop()
                container.chatService.closeSession()
                container.chatService.setMode(previous.mode)
                container.chatService.setTurnBudgets(previous.budgets)
                provider?.let { container.providerService.delete(it) }
                center.stopSession()
                center.replaceAllowlist(allowlist)
                if (services.isNullOrBlank()) {
                    shell("settings delete secure enabled_accessibility_services")
                } else {
                    shell("settings put secure enabled_accessibility_services $services")
                }
                shell("settings put secure accessibility_enabled $enabled")
                container.profileStore.switchTo(profile)
            }
            check(failure == null) { requireNotNull(failure) }
        }

    private fun awaitTerminal(session: String) {
        val deadline = SystemClock.elapsedRealtime() + 240_000
        val resolved = mutableSetOf<String>()
        while (SystemClock.elapsedRealtime() < deadline) {
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                resolveApprovals(turn.id, resolved)
                if (TurnState.valueOf(turn.state).isTerminal) return
            }
            Thread.sleep(100)
        }
        error("AndroidWorld pilot exceeded 240 seconds")
    }

    private fun resolveApprovals(
        turnId: String,
        resolved: MutableSet<String>,
    ) {
        val admitted = setOf("ui.click", "ui.long_click", "ui.set_text", "ui.scroll", "ui.back", "ui.home")
        container.storage.toolCalls.listByTurn(turnId).filter { it.state == "AWAITING_APPROVAL" }.forEach {
            val approval = container.storage.approvals.byToolCall(it.callId)
            if (approval != null && resolved.add(approval.id)) {
                // Production token, package and sensitive-UI checks remain authoritative.
                if (it.name in admitted) {
                    container.chatService.approveApproval(approval.id)
                } else {
                    container.chatService.denyApproval(approval.id)
                }
            }
        }
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }
}
