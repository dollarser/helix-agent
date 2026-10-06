package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.extensions.mobileuse.automation.backend.RootAutomationBackend
import com.helix.extensions.mobileuse.automation.backend.ShizukuAutomationBackend
import com.helix.tools.deviceaccess.DeviceAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Explicit owner opt-in, one model turn per operation, only the owned disposable APK. */
class BackendInstallModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val args get() = InstrumentationRegistry.getArguments()
    private val center get() =
        AutomationPermissionCenter(
            app,
            ShizukuAutomationBackend(app),
            RootAutomationBackend { DeviceAccess.root("mobile-use") },
        )

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught") // Keep failure evidence and restore owner state.
    fun completeOneOperation() =
        runBlocking {
            val optIn = args.getString("helixRealModel")
            org.junit.Assume.assumeTrue(optIn != null)
            require(optIn == "true")
            val backend = requireNotNull(args.getString("helixBackend"))
            require(backend in setOf("accessibility", "shizuku", "root"))
            val operation = requireNotNull(args.getString("helixOperation"))
            require(operation in setOf("install", "uninstall"))
            val trial = requireNotNull(args.getString("helixTrial"))
            require(trial.matches(Regex("[a-z0-9-]+")))
            val provider = requireNotNull(args.getString("helixProvider"))
            val model = requireNotNull(args.getString("helixModel"))
            if (!c.providerService.chatSelectable(provider)) {
                c.providerService.runConnectionTest(provider, model, verifyGeneration = true)
                check(c.providerService.chatSelectable(provider)) { "Connection probe failed" }
            }
            val original = c.chatService.screen.value.openSessionId
            val profile = c.profileStore.profile
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            val selection = MobileUseEvaluationSelection(app)
            val session = c.chatService.createSession("后端评测 $trial/$operation", provider, model)
            val started = SystemClock.elapsedRealtime()
            var failure: String? = null
            var passed = false
            try {
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                c.pluginService.setEnabled(plugin.id, true)
                selection.select(session, emptySet(), true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                prepareBackend(backend)
                assertEquals("Fixture precondition", operation == "uninstall", installed())
                c.chatService.openSession(session)
                c.chatService.setMode(AgentMode.ACT)
                c.chatService.setTurnBudgets(TurnBudgets(40, 30, 262144, 8192, 524288))
                await(10_000) { c.chatService.screen.value.openSessionId == session }
                if (operation == "install") {
                    val uri = Uri.parse("content://${app.packageName}.test.capability-state/install-ready")
                    val ready =
                        app.contentResolver.query(uri, null, null, null, null)?.use {
                            it.moveToFirst() && it.getInt(0) == 1
                        } == true
                    check(ready) { "Missing fixture in test APK cache" }
                }
                app.startActivity(initialScreen(operation))
                SystemClock.sleep(800)
                println("BACKEND_SESSION=$session;BACKEND=$backend;OPERATION=$operation")
                val task =
                    if (operation == "install") {
                        "请安装当前安装页面的 Helix Install Fixture。允许开启本次测试来源的安装许可并确认安装。"
                    } else {
                        "请卸载当前应用详情页的 Helix Install Fixture（$PACKAGE）。允许确认卸载并删除这个测试应用的数据。"
                    }
                c.chatService.sendTestMessage(
                    session,
                    task +
                        "请使用 Mobile Use 实际完成并核查结果，只操作这个测试应用及必要的系统页面，" +
                        "不使用终端，不操作其他应用或账号。",
                )
                await(420_000) {
                    c.storage.turns.listBySession(session).lastOrNull()?.let {
                        TurnState.valueOf(it.state).isTerminal
                    } == true
                }
                passed = c.storage.turns
                    .listBySession(session)
                    .last()
                    .state == "COMPLETED" &&
                    installed() == (operation == "install")
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
            } finally {
                c.chatService.stop()
                val evidence =
                    buildJsonObject {
                        put("session", session)
                        put("backend", backend)
                        put("operation", operation)
                        put("passed", passed)
                        put("failure", failure)
                        put("installed", installed())
                        put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        put("trajectory", evaluationTrajectory(c, session))
                    }
                File(app.filesDir, "backend-eval")
                    .apply { mkdirs() }
                    .resolve("$trial-$operation.json")
                    .writeText(evidence.toString())
                println("BACKEND_RESULT=$evidence")
                selection.close()
                c.pluginService.setEnabled(plugin.id, plugin.enabled)
                c.profileStore.switchTo(profile)
                if (backend == "root") DeviceAccess.disconnectRoot("mobile-use")
                if (original != null) c.chatService.openSession(original) else c.chatService.closeSession()
            }
            assertTrue("Task failed; retained session $session", passed)
        }

    private fun initialScreen(operation: String): Intent {
        val intent =
            if (operation == "install") {
                val activity = AutomationInstallSourceActivity::class.java.name
                val component = ComponentName("${app.packageName}.test", activity)
                Intent().setComponent(component)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PACKAGE"))
            }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun prepareBackend(backend: String) {
        if (backend == "root") {
            DeviceAccess.requestRootFromUser("mobile-use")
            await(60_000) { DeviceAccess.root("mobile-use")?.cachedAppGrant == true }
            DeviceAccess.connectRoot("mobile-use")
            await(60_000) { DeviceAccess.root("mobile-use")?.connectedBinder() != null }
        }
        if (backend == "accessibility") {
            await(15_000) { center.serviceState() == AutomationServiceState.CONNECTED }
        } else {
            assertTrue("Accessibility connected", center.serviceState() != AutomationServiceState.CONNECTED)
        }
        await(15_000) { center.preferredBackend(AutomationDeviceOperation.SNAPSHOT)?.name?.lowercase() == backend }
        val actual = center.preferredBackend(AutomationDeviceOperation.SNAPSHOT)
        assertEquals(backend, actual?.name?.lowercase())
        println("BACKEND_CONFIRMED=$actual")
    }

    private fun installed() = runCatching { app.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    private fun await(
        timeout: Long,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        check(condition()) { "Timed out after $timeout ms" }
    }

    companion object {
        const val PACKAGE = "com.helix.validation.installfixture"
    }
}
