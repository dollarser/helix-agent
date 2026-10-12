package com.helix.app.eval

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.openTestSessionControl
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.model.TurnBudgets
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationNodeAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.PermissionCenterAutomationToolPort
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Owner-opt-in native input/observation regression; no model or third-party application. */
class AccessibilityInputObservationDeviceTest {
    @Test
    @Suppress("LongMethod") // Keep the bounded real-platform transition and exact restoration together.
    fun freshObservationRecoversAfterInput() =
        runBlocking {
            val inst = InstrumentationRegistry.getInstrumentation()
            assumeTrue(InstrumentationRegistry.getArguments().getString("helixAccessibilityInput") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            val automation = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val original = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
            val enabled = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
            val profile = c.profileStore.profile
            val session = "input-observation-${System.nanoTime()}"
            val server = LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).apply { start() }
            var provider: String? = null
            var streamer: Job? = null
            val target = "${app.packageName}.test"
            val packages = setOf(app.packageName, target)
            val events = ConcurrentHashMap<String, Int>()
            automation.setOnAccessibilityEventListener { event ->
                val pkg = event.packageName?.toString()
                if (pkg in packages) events.merge("$pkg:${event.windowId}:${event.eventType}", 1, Int::plus)
            }
            c.firstLaunch.markSeen()
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val component = "${app.packageName}/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
            val services =
                (
                    original
                        .orEmpty()
                        .split(
                            ':',
                        ).filter(String::isNotBlank) + component
                ).distinct().joinToString(":")
            try {
                automation.executeShellCommand("settings put secure enabled_accessibility_services $services").close()
                automation.executeShellCommand("settings put secure accessibility_enabled 1").close()
                val center = AutomationPermissionCenter(app)
                waitFor { center.serviceState() == AutomationServiceState.CONNECTED }
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                provider =
                    runBlocking {
                        c.providerService.create(
                            ProviderDraft(
                                null,
                                "Held input fixture",
                                ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                                NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                                "fixture-model-a",
                                "{}",
                                false,
                                CleartextWarning("127.0.0.1", server.port),
                                emptyList(),
                            ),
                            null,
                        )
                    }
                check(runBlocking { c.providerService.runConnectionTest(requireNotNull(provider)) } is ProbeOutcome.Ok)
                c.storage.sessions.create(
                    session,
                    "Input observation fixture",
                    provider,
                    "fixture-model-a",
                    System.currentTimeMillis(),
                )
                check(selectMobileUseForTest(session, packages, false))
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.chatService.openTestSessionControl(session, AgentMode.ACT, TurnBudgets(24, 24, 524288, 8192, 557056))
                server.forceTextResponses = true
                server.holdChatStreams.set(true)
                c.chatService.sendTestMessage(session, "Hold the synthetic input fixture")
                waitFor { server.heldStreams.get() == 1 }
                streamer =
                    launch(Dispatchers.IO) {
                        val output = server.heldSocket.get().getOutputStream()
                        while (isActive) {
                            val delta =
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" +
                                    "progress ".repeat(20) + "\"},\"finish_reason\":null}]}\n\n"
                            output.write(delta.toByteArray())
                            output.flush()
                            delay(40)
                        }
                    }
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .single()
                        .id
                app.startActivity(
                    Intent()
                        .setComponent(ComponentName(target, AutomationEvaluationActivity::class.java.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("modelJourney", true),
                )
                val port =
                    PermissionCenterAutomationToolPort(center).forCall(
                        ExecutableToolCall(
                            "observe",
                            "ui.snapshot",
                            "5",
                            buildJsonObject {},
                            ExecutionTargetType.LOCAL_ANDROID,
                            Instant.now().plusSeconds(60),
                            NoCancellation,
                            sessionId = session,
                            turnId = turn,
                            authorizationScopeRef = center.conversationGrant(session)?.scope?.toScopeRef(),
                        ),
                    )
                c.toolPipeline.dispatcher.dispatch(
                    ToolDispatchRequest(
                        toolCallId = "$turn-prime",
                        turnId = turn,
                        sessionId = session,
                        toolName = ToolName("ui.snapshot"),
                        toolVersion = requireNotNull(c.toolPipeline.resolveLatest("ui.snapshot")).version,
                        args = buildJsonObject {},
                        mode = AgentMode.ACT,
                        profile = SafetyProfile.ADVANCED,
                        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                        dataOrigin = DataOrigin.WORKSPACE,
                        scope = requireNotNull(center.conversationGrant(session)).scope,
                        uiToken = "chat:$turn",
                    ),
                )
                var before = port.snapshot()
                waitFor {
                    before = port.snapshot()
                    before.status == AutomationSnapshotStatus.SUCCESS
                }
                val input = requireNotNull(before.snapshot).nodes.single { it.editable }
                // Isolate native input from the observation wrapper under test. Real model
                // journeys separately exercise dispatched set_text and its stale-token guard.
                val action =
                    port.nodeAction(
                        AutomationNodeActionRequest(AutomationNodeAction.SET_TEXT, input.token, "你好 Helix"),
                    )
                assertTrue("$action events=$events", action.status == AutomationActionStatus.SUCCEEDED)
                val observations =
                    (0 until 10).map { index ->
                        SystemClock.sleep(300)
                        val reply =
                            c.toolPipeline.dispatcher.dispatch(
                                ToolDispatchRequest(
                                    toolCallId = "$turn-observe-$index",
                                    turnId = turn,
                                    sessionId = session,
                                    toolName = ToolName("ui.snapshot"),
                                    toolVersion = requireNotNull(c.toolPipeline.resolveLatest("ui.snapshot")).version,
                                    args = buildJsonObject {},
                                    mode = AgentMode.ACT,
                                    profile = SafetyProfile.ADVANCED,
                                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                                    dataOrigin = DataOrigin.WORKSPACE,
                                    scope = requireNotNull(center.conversationGrant(session)).scope,
                                    uiToken = "chat:$turn",
                                ),
                            ) as ToolDispatchOutcome.Succeeded
                        Json.parseToJsonElement(reply.result.payload).jsonObject.also { result ->
                            println(
                                "INPUT_OBSERVATION status=${result["status"]} generation=${result["generation"]} " +
                                    "target=${result["targetPackage"]} events=$events",
                            )
                        }
                    }
                assertTrue(
                    "Fresh input must become observable: ${observations.map { it["status"] }}",
                    observations.all {
                        it["status"]?.jsonPrimitive?.content == "SUCCESS" && it["nodes"]?.jsonArray?.any { node ->
                            node.jsonObject["editable"]?.jsonPrimitive?.content == "true" &&
                                node.jsonObject["text"]?.jsonPrimitive?.content == "你好 Helix"
                        } == true
                    },
                )
            } finally {
                streamer?.cancelAndJoin()
                automation.setOnAccessibilityEventListener(null)
                c.storage.turns
                    .listBySession(session)
                    .forEach { c.chatService.stopTurn(it.id) }
                deselectMobileUseForTest(session)
                c.storage.sessions
                    .find(session)
                    ?.let { c.storage.deleteSessionPermanently(session) }
                provider?.let { c.providerService.delete(it) }
                server.close()
                c.profileStore.switchTo(profile)
                automation
                    .executeShellCommand(
                        if (original.isNullOrBlank()) {
                            "settings delete secure enabled_accessibility_services"
                        } else {
                            "settings put secure enabled_accessibility_services $original"
                        },
                    ).close()
                automation.executeShellCommand("settings put secure accessibility_enabled $enabled").close()
            }
        }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(predicate()) { "Input observation fixture deadline" }
    }
}
