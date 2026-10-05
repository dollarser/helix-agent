package com.helix.app.eval

import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class CurrentInstallVisualGestureDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val center get() = AutomationPermissionCenter(app)

    @Test
    @Suppress("LongMethod")
    fun visionScreenshotAndGestureInstallsCurrentWeChat() =
        runBlocking {
            val enabled = InstrumentationRegistry.getArguments().getString(ARG_ENABLED)
            assumeTrue(enabled != null)
            require(enabled == "true") { "$ARG_ENABLED must be true when supplied" }
            assertFalse("fixture requires WeChat to be uninstalled", isInstalled())
            val previousProfile = container.profileStore.profile
            val previousServices =
                android.provider.Settings.Secure.getString(
                    app.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            val previousEnabled =
                android.provider.Settings.Secure.getInt(
                    app.contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    0,
                )
            var provider: String? = null
            var session: String? = null
            try {
                ensureAccessibility(previousServices)
                container.profileStore.switchTo(SafetyProfile.ADVANCED)
                provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Current Install visual gesture",
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
                assertTrue(container.providerService.runConnectionTest(provider, MODEL) is ProbeOutcome.Ok)
                val capability = container.providerService.runCapabilityTest(provider, MODEL)
                assertTrue("vision probe failed", capability is ProbeOutcome.Ok && capability.capabilities.vision)

                session = container.chatService.createSession("Current Install visual gesture", provider, MODEL)
                container.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                com.helix.app.eval
                    .selectMobileUseForTest(session, emptySet(), wholePhone = true)
                container.chatService.openSession(session)
                container.chatService.setMode(AgentMode.ACT)
                awaitMobileUseProjection(session)

                container.chatService.sendTestMessage(
                    session,
                    "The current foreground screen is the Android installer for WeChat. " +
                        "Use ui.device and ui.screenshot to inspect the current frame visually. " +
                        "Then click the visible Install button using ui.gesture on that same frame. " +
                        "Do not use ui.find, ui.wait, ui.click, or ui.click_match. " +
                        "Do not use coordinates unless they come from the screenshot you just inspected. " +
                        "After the gesture, observe the result and finish only after installation has completed.",
                )
                awaitTerminal(session)

                val turn =
                    requireNotNull(
                        container.storage.turns
                            .listBySession(session)
                            .lastOrNull(),
                    )
                val calls = container.storage.toolCalls.listByTurn(turn.id)
                assertEquals("turn did not complete", TurnState.COMPLETED.name, turn.state)
                println("VISUAL_INSTALL_CALLS=${calls.map { it.name + ":" + it.argsJson }}")
                assertTrue("nonvisual tools participated", calls.all { it.name in VISUAL_TOOLS })
                assertVisualTrace(calls)
                assertTrue("ui.screenshot missing", calls.any { it.name == "ui.screenshot" })
                assertTrue("ui.gesture missing", calls.any { it.name == "ui.gesture" })
                assertFalse(
                    "semantic click must not participate",
                    calls.any { it.name == "ui.click" || it.name == "ui.click_match" },
                )
                assertFalse(
                    "semantic find/wait must not participate",
                    calls.any { it.name == "ui.find" || it.name == "ui.wait" },
                )

                val screenshotResults =
                    calls.filter { it.name == "ui.screenshot" }.mapNotNull { call ->
                        container.storage.toolResults.byToolCall(call.id)?.let {
                            container.storage.toolResults.readContent(it)
                        }
                    }
                println("VISUAL_INSTALL_SCREENSHOTS=$screenshotResults")
                assertTrue(
                    "screenshot pixels were not delivered",
                    screenshotResults.any { it.contains("\"pixelsAttached\":true") },
                )
                waitUntil(60_000) { isInstalled() }
                assertTrue("visual gesture did not install WeChat", isInstalled())
            } finally {
                container.chatService.stop()
                container.chatService.closeSession()
                session?.let(::deselectMobileUseForTest)
                provider?.let { container.providerService.delete(it) }
                restoreAccessibility(previousServices, previousEnabled)
                container.profileStore.switchTo(previousProfile)
            }
        }

    private fun assertVisualTrace(calls: List<ToolCallEntity>) {
        var screenshotFrame: String? = null
        var successfulGestures = 0
        calls.forEach { call ->
            when (call.name) {
                "ui.device" -> {
                    screenshotFrame = null
                }

                "ui.screenshot" -> {
                    val result = resultContent(call)
                    assertEquals("SAVED", result["status"]?.jsonPrimitive?.content)
                    assertEquals(true, result["pixelsAttached"]?.jsonPrimitive?.booleanOrNull)
                    screenshotFrame = result["frame"]?.jsonPrimitive?.content
                    assertFalse("missing screenshot frame", screenshotFrame.isNullOrBlank())
                    assertEquals(
                        screenshotFrame,
                        Json
                            .parseToJsonElement(call.argsJson)
                            .jsonObject["frame"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                }

                "ui.gesture" -> {
                    val frame =
                        Json
                            .parseToJsonElement(call.argsJson)
                            .jsonObject["frame"]
                            ?.jsonPrimitive
                            ?.content
                    assertFalse("gesture before delivered screenshot", screenshotFrame.isNullOrBlank())
                    assertEquals("gesture used a different frame", screenshotFrame, frame)
                    assertEquals("SUCCEEDED", resultContent(call)["status"]?.jsonPrimitive?.content)
                    screenshotFrame = null
                    successfulGestures++
                }
            }
        }
        assertTrue("no successful visual gesture", successfulGestures > 0)
        val lastGesture = calls.indexOfLast { it.name == "ui.gesture" }
        assertTrue("missing post-action observation", calls.drop(lastGesture + 1).any { it.name in OBSERVATION_TOOLS })
    }

    private fun resultContent(call: ToolCallEntity): JsonObject {
        val result = requireNotNull(container.storage.toolResults.byToolCall(call.id))
        assertTrue("result was not verified: ${call.name}", result.verified)
        return Json.parseToJsonElement(requireNotNull(container.storage.toolResults.readContent(result))).jsonObject
    }

    private fun ensureAccessibility(previousServices: String?) {
        val component = "${app.packageName}/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
        val previous = previousServices.orEmpty().split(':').filter(String::isNotBlank)
        val all = (previous.filterNot { it == component } + component).distinct()
        shell("settings put secure enabled_accessibility_services ${all.joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
        waitUntil(20_000) { center.serviceState() == AutomationServiceState.CONNECTED }
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

    private fun awaitMobileUseProjection(session: String) {
        waitUntil(10_000) {
            container.chatService.screen.value.openSessionId == session &&
                com.helix.app.automation.AutomationModule
                    .scopeFor("ui.snapshot", session) != null
        }
    }

    private fun awaitTerminal(session: String) {
        waitUntil(120_000) {
            container.storage.turns
                .listBySession(session)
                .lastOrNull()
                ?.let { TurnState.valueOf(it.state).isTerminal } == true
        }
    }

    private fun isInstalled(): Boolean =
        try {
            app.packageManager.getPackageInfo(WECHAT_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun waitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        check(condition()) { "condition was not met within ${timeoutMillis}ms" }
    }

    private companion object {
        const val ARG_ENABLED = "helixPackageInstallerVisualFallback"
        const val MODEL = "Qwen3.8-27B"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        val OBSERVATION_TOOLS = setOf("ui.device", "ui.screenshot", "ui.apps")
        val VISUAL_TOOLS = OBSERVATION_TOOLS + "ui.gesture"
    }
}
