package com.helix.app.eval

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

/** Owner-prepared update dialog; no UiAutomation connection competing with the shell dump. */
class ShizukuMobileUseDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val center get() = AutomationPermissionCenter(app)

    @Test
    @Suppress("LongMethod")
    fun productionDispatcherRejectsUnauthorizedCallsThenUpdatesWechat() {
        val enabled = InstrumentationRegistry.getArguments().getString("helixShizukuUpdate")
        assumeTrue(enabled != null)
        require(enabled == "true")
        val rootMode = InstrumentationRegistry.getArguments().getString("helixRootInstaller") == "true"
        val confirmText = InstrumentationRegistry.getArguments().getString("helixConfirmText") ?: "Update"
        require(confirmText in setOf("Install", "INSTALL", "Update", "UPDATE"))
        val installerPackage =
            InstrumentationRegistry.getArguments().getString("helixInstallerPackage")
                ?: "com.google.android.packageinstaller"
        require(installerPackage in setOf("com.google.android.packageinstaller", "com.android.packageinstaller"))
        println("SHIZUKU_WAITING_ACCESSIBILITY_REBIND")
        val connectedDeadline = SystemClock.elapsedRealtime() + 30_000
        while (center.serviceState() != AutomationServiceState.CONNECTED &&
            SystemClock.elapsedRealtime() < connectedDeadline
        ) {
            SystemClock.sleep(100)
        }
        assertEquals(AutomationServiceState.CONNECTED, center.serviceState())
        val originalProfile = container.profileStore.profile
        val session = "shizuku-fixture-${System.nanoTime()}"
        val turn = "$session-turn"
        val now = System.currentTimeMillis()
        val before = packageTime()
        container.storage.sessions.create(session, "Shizuku integration fixture", null, null, now)
        container.storage.turns.start(turn, session, now)
        val plugin = container.pluginService.list().single { it.native?.pluginId == "mobile-use" }
        require(plugin.enabled) { "Mobile Use must already be enabled" }
        container.pluginService.catalog.select(session, plugin.id, true)
        try {
            container.profileStore.switchTo(SafetyProfile.ADVANCED)
            if (rootMode) prepareRoot()
            center.authorizeConversation(session, emptySet(), wholePhone = true)
            if (rootMode) awaitStableInstaller(session, installerPackage)
            val descriptor = requireNotNull(container.toolPipeline.resolveLatest("ui.click_match"))

            fun request(
                id: String,
                text: String = confirmText,
            ) = ToolDispatchRequest(
                toolCallId = "$session-$id",
                turnId = turn,
                sessionId = session,
                toolName = ToolName("ui.click_match"),
                toolVersion = descriptor.version,
                args =
                    buildJsonObject {
                        put("backend", "auto")
                        put("packageName", installerPackage)
                        put("viewId", if (rootMode) "$installerPackage:id/button1" else "android:id/button1")
                        put("text", text)
                    },
                mode = AgentMode.ACT,
                profile = SafetyProfile.ADVANCED,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                dataOrigin = DataOrigin.WORKSPACE,
                scope = requireNotNull(center.conversationGrant(session)).scope,
                uiToken = "chat:$turn",
            )
            container.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                now,
            )
            val denied = container.toolPipeline.dispatcher.dispatch(request("readonly"))
            println("SHIZUKU_READ_ONLY=$denied")
            assertTrue(denied is ToolDispatchOutcome.Denied)
            assertEquals(before, packageTime())
            container.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                now + 1,
            )
            val oldScope = request("stale-grant")
            center.authorizeConversation(session, setOf(app.packageName), wholePhone = false)
            val stale = container.toolPipeline.dispatcher.dispatch(oldScope)
            println("SHIZUKU_STALE_SCOPE=$stale")
            assertTrue(stale !is ToolDispatchOutcome.Succeeded)
            val outside = container.toolPipeline.dispatcher.dispatch(request("outside-scope"))
            println("SHIZUKU_OUTSIDE_SCOPE=$outside")
            assertTrue(outside !is ToolDispatchOutcome.Succeeded)
            assertEquals(before, packageTime())
            center.authorizeConversation(session, emptySet(), wholePhone = true)
            val missing = container.toolPipeline.dispatcher.dispatch(request("missing", "HELIX_NO_SUCH_CONTROL"))
            println("SHIZUKU_MISSING_TARGET=$missing")
            assertTrue(missing is ToolDispatchOutcome.ExecutionFailed && missing.sideEffectFree)
            assertEquals("TARGET_NOT_FOUND", (missing as ToolDispatchOutcome.ExecutionFailed).detail)
            assertEquals(before, packageTime())
            val successRequest = request("update")
            val success = container.toolPipeline.dispatcher.dispatch(successRequest)
            println("SHIZUKU_PRODUCTION_CLICK=$success")
            assertTrue("$success", success is ToolDispatchOutcome.Succeeded)
            val expectedBackend = if (rootMode) "root" else "shizuku"
            assertTrue("$success", success.toString().contains("\"backend\":\"$expectedBackend\""))
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (packageTime() <= before && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
            assertTrue("No new package update", packageTime() > before)
            val audit = container.storage.auditEvents.listByCorrelation(successRequest.toolCallId)
            assertEquals(1, audit.count { it.type == "tool_dispatch" })
            println("SHIZUKU_PRODUCTION_UPDATE=$before->${packageTime()};audit=${audit.map { it.type }}")
        } finally {
            if (rootMode) MobileUseRootConnection.disconnect()
            center.revokeConversation(session)
            container.storage.deleteSessionPermanently(session)
            container.profileStore.switchTo(originalProfile)
        }
    }

    private fun packageTime(): Long =
        try {
            app.packageManager.getPackageInfo("com.tencent.mm", 0).lastUpdateTime
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            0L
        }

    private fun prepareRoot() {
        MobileUseRootConnection.requestFromUser()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (MobileUseRootConnection.access()?.connectedBinder() == null &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(100)
        }
        checkNotNull(MobileUseRootConnection.access()?.connectedBinder())
    }

    private fun awaitStableInstaller(
        session: String,
        installer: String,
    ) {
        val call =
            com.helix.tools.framework.ExecutableToolCall(
                "observe-installer",
                "ui.device",
                "3",
                buildJsonObject {},
                ExecutionTargetType.LOCAL_ANDROID,
                java.time.Instant
                    .now()
                    .plusSeconds(30),
                com.helix.tools.framework.NoCancellation,
                sessionId = session,
                authorizationScopeRef = center.conversationGrant(session)?.scope?.toScopeRef(),
            )
        val port =
            com.helix.tools.automation
                .PermissionCenterDevicePort(center)
                .forCall(call)
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var previous: com.helix.tools.automation.AutomationDisplayTarget? = null
        var stable = 0
        while (stable < 6 && SystemClock.elapsedRealtime() < deadline) {
            val current = port.observe().frame?.target
            stable = if (current?.packageName == installer && current == previous) stable + 1 else 0
            previous = current
            SystemClock.sleep(500)
        }
        assertEquals("Installer must stabilize after root authorization", 6, stable)
    }
}
