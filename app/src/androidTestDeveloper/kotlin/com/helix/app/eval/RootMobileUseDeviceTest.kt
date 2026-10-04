package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.automation.shizuku.MobileUseRootConnection
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.policy.DataOrigin
import com.helix.core.policy.SessionPermissionConfig
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Registered plugin + Dispatcher, optionally recording Root/Shizuku short presses on our fixture. */
@Suppress("TooManyFunctions") // One device fixture shares observation/dispatch helpers across both privileged backends.
class RootMobileUseDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val center get() = AutomationPermissionCenter(app)
    private val target get() = "${app.packageName}.test"
    private val session = "root-mobile-${System.nanoTime()}"
    private val turn = "$session-turn"

    @Test
    @Suppress("LongMethod") // Keep setup, verification and restoration in one bounded device lifecycle.
    fun autoUsesGrantedRootThenAccessibilityAfterExplicitLoss() {
        val option = InstrumentationRegistry.getArguments().getString("helixRootMobileUse")
        assumeTrue(option != null)
        require(option == "true")
        val touchBackend = InstrumentationRegistry.getArguments().getString("helixTouchBackend")
        require(touchBackend == null || touchBackend in setOf("root", "shizuku"))
        val rootMode = touchBackend != "shizuku"
        println("ROOT_WAITING_ACCESSIBILITY_REBIND")
        await { center.serviceState() == AutomationServiceState.CONNECTED }
        val oldProfile = container.profileStore.profile
        val now = System.currentTimeMillis()
        container.storage.sessions.create(session, "Root Mobile Use fixture", null, null, now)
        container.storage.turns.start(turn, session, now)
        val plugin = container.pluginService.list().single { it.native?.pluginId == "mobile-use" }
        require(plugin.enabled)
        container.pluginService.catalog.select(session, plugin.id, true)
        try {
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            center.authorizeConversation(session, emptySet(), wholePhone = true)
            app.startActivity(
                Intent(app, com.helix.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            await { snapshot()?.packageName == app.packageName }
            if (rootMode) {
                MobileUseRootConnection.requestFromUser()
                await { MobileUseRootConnection.access()?.connectedBinder() != null }
            }
            app.startActivity(
                Intent()
                    .setComponent(ComponentName(target, AutomationEvaluationActivity::class.java.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("recordClicks", true)
                    .putExtra("recordTouches", touchBackend != null),
            )
            await { snapshot()?.packageName == target }
            if (rootMode) assertTrue(MobileUseRootConnection.access()?.connectedBinder() != null)
            permission(SessionPermissionMode.READ_ONLY)
            assertTrue(dispatch("readonly") is ToolDispatchOutcome.Denied)
            permission(SessionPermissionMode.FULL_ACCESS)
            rejectStaleAndOutsideScopes()
            awaitStableFixture()
            val missing = dispatch("missing", text = "HELIX_ABSENT") as ToolDispatchOutcome.ExecutionFailed
            assertEquals("TARGET_NOT_FOUND", missing.detail)
            assertTrue(missing.sideEffectFree)
            if (touchBackend != null) {
                verifyShortPresses(touchBackend)
                return
            }
            val root = dispatch("root")
            assertTrue("$root", root is ToolDispatchOutcome.Succeeded)
            assertTrue("$root", root.toString().contains("\"backend\":\"root\""))
            awaitClicks(1)
            assertEquals(
                1,
                container.storage.auditEvents.listByCorrelation("$session-root").count {
                    it.type ==
                        "tool_dispatch"
                },
            )
            verifyLossAndFreshFallback()
            println("ROOT_MOBILE_USE_PASSED=root->accessibility;clicks=2;root=$root")
        } finally {
            MobileUseRootConnection.disconnect()
            center.revokeConversation(session)
            container.storage.deleteSessionPermanently(session)
            container.profileStore.switchTo(oldProfile)
        }
    }

    private fun verifyShortPresses(backend: String) {
        val cancelled =
            request("cancelled", backend).copy(
                cancel =
                    object : com.helix.tools.framework.CancelSignal {
                        override fun isCancelled() = true
                    },
            )
        assertEquals(ToolDispatchOutcome.Cancelled, container.toolPipeline.dispatcher.dispatch(cancelled))
        for (count in 1..3) {
            awaitStableFixture()
            var targetText: String? = null
            await {
                targetText = snapshot()?.nodes?.singleOrNull { it.viewId == "android:id/button1" }?.text
                targetText != null
            }
            val outcome = dispatch("press-$count", backend, requireNotNull(targetText))
            assertTrue("$outcome", outcome is ToolDispatchOutcome.Succeeded)
            assertTrue("$outcome", outcome.toString().contains("\"backend\":\"$backend\""))
            awaitClicks(count)
            var touch: String? = null
            await {
                touch =
                    snapshot()?.nodes?.mapNotNull { it.text }?.singleOrNull { it.startsWith("TOUCH:$count:$count:") }
                touch != null
            }
            val fields = requireNotNull(touch).split(':')
            assertEquals(count, fields[1].toInt())
            assertEquals(count, fields[2].toInt())
            // The request is 60..120 ms; event processing can extend it on a loaded emulator.
            assertTrue("Unexpected measured duration: $touch", fields[3].toLong() in 60L..250L)
            assertEquals("true", fields[4])
            assertEquals(
                1,
                container.storage.auditEvents.listByCorrelation("$session-press-$count").count {
                    it.type ==
                        "tool_dispatch"
                },
            )
            reportTouch("TOUCH_PRESS_RESULT=$backend;$touch;clicks=$count")
        }
        SystemClock.sleep(300)
        awaitClicks(3)
        reportTouch(
            "TOUCH_PRESS_PASSED=$backend;clicks=3;pre-cancelled=refused;readonly=denied;stale=refused;outside=refused",
        )
    }

    private fun reportTouch(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            android.os.Bundle().apply { putString("stream", "$message\n") },
        )
    }

    private fun awaitStableFixture() =
        await {
            val before = snapshot()
            SystemClock.sleep(300)
            val after = snapshot()
            before?.packageName == target && after?.packageName == target && before.generation == after.generation
        }

    private fun verifyLossAndFreshFallback() {
        val oldBinder = requireNotNull(MobileUseRootConnection.access()?.connectedBinder())
        MobileUseRootConnection.disconnect()
        await { MobileUseRootConnection.access()?.connectedBinder() == null }
        await { !oldBinder.pingBinder() }
        val refused = dispatch("lost", backend = "root") as ToolDispatchOutcome.ExecutionFailed
        assertEquals("ROOT_UNAVAILABLE", refused.detail)
        assertTrue(refused.sideEffectFree)
        await {
            val before = snapshot()
            SystemClock.sleep(300)
            val after = snapshot()
            before?.packageName == target && after?.packageName == target && before.generation == after.generation
        }
        val fallback = dispatch("fallback")
        assertTrue("$fallback", fallback is ToolDispatchOutcome.Succeeded)
        assertTrue("$fallback", fallback.toString().contains("\"backend\":\"accessibility\""))
        awaitClicks(2)
    }

    private fun rejectStaleAndOutsideScopes() {
        val old = request("stale")
        center.authorizeConversation(session, setOf(app.packageName), wholePhone = false)
        assertTrue(container.toolPipeline.dispatcher.dispatch(old) !is ToolDispatchOutcome.Succeeded)
        val outside = dispatch("outside") as ToolDispatchOutcome.ExecutionFailed
        assertEquals("TARGET_NOT_ALLOWLISTED", outside.detail)
        center.authorizeConversation(session, emptySet(), wholePhone = true)
    }

    private fun permission(mode: SessionPermissionMode) {
        container.sessionPermissionEdit.saveSessionConfig(
            session,
            SessionPermissionConfig.of(mode),
            System.currentTimeMillis(),
        )
    }

    private fun dispatch(
        id: String,
        backend: String = "auto",
        text: String = "FIXTURE CLICK",
    ) = container.toolPipeline.dispatcher.dispatch(request(id, backend, text))

    private fun request(
        id: String,
        backend: String = "auto",
        text: String = "FIXTURE CLICK",
    ) = ToolDispatchRequest(
        toolCallId = "$session-$id",
        turnId = turn,
        sessionId = session,
        toolName = ToolName("ui.click_match"),
        toolVersion = requireNotNull(container.toolPipeline.resolveLatest("ui.click_match")).version,
        args =
            buildJsonObject {
                put("backend", backend)
                put("packageName", target)
                put("viewId", "android:id/button1")
                put("text", text)
            },
        mode = AgentMode.ACT,
        profile = SafetyProfile.ADVANCED,
        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
        dataOrigin = DataOrigin.WORKSPACE,
        scope = requireNotNull(center.conversationGrant(session)).scope,
        uiToken = "chat:$turn",
    )

    private fun callForObservation() =
        com.helix.tools.framework.ExecutableToolCall(
            "observe",
            "ui.snapshot",
            "5",
            buildJsonObject {},
            ExecutionTargetType.LOCAL_ANDROID,
            java.time.Instant
                .now()
                .plusSeconds(30),
            com.helix.tools.framework.NoCancellation,
            sessionId = session,
            authorizationScopeRef = center.conversationGrant(session)?.scope?.toScopeRef(),
        )

    private fun awaitClicks(count: Int) =
        await {
            snapshot()?.nodes?.any { it.contentDescription == "fixture-click-count:$count" } == true
        }

    private fun snapshot() =
        com.helix.tools.automation
            .PermissionCenterAutomationToolPort(center)
            .forCall(callForObservation())
            .snapshot()
            .snapshot

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Condition timed out")
    }
}
