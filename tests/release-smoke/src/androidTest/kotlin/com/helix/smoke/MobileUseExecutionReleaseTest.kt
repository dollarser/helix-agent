package com.helix.smoke

import android.app.UiAutomation
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real R8 app, UI-created settings, real Dispatcher and read-only durable facts. No production test hooks. */
@RunWith(AndroidJUnit4::class)
class MobileUseExecutionReleaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val ui = ReleaseUiTestSupport()
    private val fixturePackage = instrumentation.context.packageName

    @Test fun requestedExecutionBoundary() {
        val boundary = InstrumentationRegistry.getArguments().getString("boundary")
        require(boundary in setOf("cancel", "unknown", "stale"))
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val database = ReleaseDatabaseProbe(automation, ui.context.packageName)
        ReleaseFixtureState.clicks.set(0)
        ReleaseFixtureState.downs.set(0)
        ReleaseModelFixture(fixturePackage).use { model ->
            configure(model)
            try {
                when (boundary) {
                    "cancel" -> cancel(database, model)
                    "unknown" -> unknown(database, model)
                    "stale" -> stale(database, model)
                }
            } finally {
                record("R8_LAST_FACTS=${database.diagnostic()}")
                val counts = "downs:${ReleaseFixtureState.downs.get()},clicks:${ReleaseFixtureState.clicks.get()}"
                record("R8_FIXTURE_COUNTS=$counts")
            }
            check(model.failure.get() == null) { "Loopback fixture failed: ${model.failure.get()}" }
            record("R8_EXECUTION_BOUNDARY=$boundary;passed;target=${ui.context.packageName}")
        }
    }

    private fun configure(model: ReleaseModelFixture) {
        ui.ready()
        ui.activate("应用导航", "App navigation")
        if (ui.node("切换到 Advanced", "Switch to Advanced") != null) {
            ui.activate("切换到 Advanced", "Switch to Advanced")
            ui.activate("我已了解，确认切换", "I understand, switch")
        }
        ui.activate("模型", "Models")
        ui.activate("添加 API", "Add API")
        ui.scrollTo("自定义服务", "Custom service")
        ui.activate("自定义服务", "Custom service")
        ui.editField("R8 fixture", "名称", "Name")
        ui.editField(model.endpoint, "API 地址", "API endpoint")
        ui.scrollTo("＋ 添加模型", "+ Add model")
        ui.activate("＋ 添加模型", "+ Add model")
        ui.editField("r8-fixture", "模型名称（服务端标识）", "Model name (server ID)")
        ui.activate("保存", "Save")
        ui.activate("连接测试", "Connection test")
        ui.await("连接已通过 · 能力未检测", "Connected · capabilities not checked", timeoutMillis = 30_000)
        ui.activate("高级选项", "Advanced options")
        ui.scrollTo("能力检测", "Detect capabilities")
        ui.activate("能力检测", "Detect capabilities")
        ui.await("已通过 · 能力已探测", "Passed · capabilities detected", timeoutMillis = 30_000)
        ui.back()
        configureScope()
        currentConversation()
        ui.activate("需要批准 ▾", "Approval required ▾")
        ui.activate("完全访问", "Full access")
        ui.await("完全访问 ▾", "Full access ▾")
        if (ui.node("选择模型 ▾", "Select model ▾") != null) {
            ui.activate("选择模型 ▾", "Select model ▾")
            ui.activate("r8-fixture")
        }
        ui.await("r8-fixture ▾")
        ui.await("执行", "Act")
    }

    private fun configureScope() {
        ui.activate("应用导航", "App navigation")
        ui.activate("扩展", "Extensions")
        ui.enterText("mobile-use")
        ui.activate("Mobile Use")
        ui.activate("插件设置", "Plugin settings")
        ui.await("选择应用（已选 0 个）", "Choose apps (0 selected)")
        ui.scrollTo("选择应用（已选 0 个）", "Choose apps (0 selected)")
        ui.toggleChoice()
        ui.activate("选择应用（已选 0 个）", "Choose apps (0 selected)")
        for (packageName in listOf(fixturePackage, ui.context.packageName)) {
            ui.enterText(packageName)
            ui.activate(packageName)
            await("App choice $packageName") { ui.checkedChoice(packageName) }
        }
        ui.activate("确认选择", "Confirm selection")
        ui.activate("保存插件设置", "Save plugin settings")
        ui.await("已保存，对启用此插件的会话生效", "Saved for conversations using this plugin")
        ui.back()
        ui.activate("Mobile Use")
        ui.scrollTo("在会话中使用", "Use in a conversation")
        ui.activate("在会话中使用", "Use in a conversation")
        ui.await("mobile-use")
        ui.toggleChoice()
        await("Mobile Use selection") { ui.nodes().singleOrNull { it.isCheckable }?.isChecked == true }
        ui.back()
    }

    private fun currentConversation() {
        ui.activate("应用导航", "App navigation")
        ui.activate("当前会话", "Current conversation")
    }

    private fun submit(phase: String) {
        ui.await("r8-fixture ▾")
        ui.enterText(
            "R8_BOUNDARY=$phase Only operate the disposable R8 fixture. No third-party apps or accounts.",
            focus = true,
        )
        ui.click("发送", "Send")
    }

    private fun cancel(
        database: ReleaseDatabaseProbe,
        model: ReleaseModelFixture,
    ) {
        submit("cancel")
        await("real wait running", model) { database.call("r8-cancel-wait").startsWith("RUNNING||RUNNING_TOOL|") }
        ui.resumeTarget()
        ui.activate("停止", "Stop")
        await("cancelled durable wait", model) {
            database.call("r8-cancel-wait").startsWith("FAILED|FAILED|CANCELLED|AUTOMATION_WAIT_CANCELLED|")
        }
        val fact = database.call("r8-cancel-wait")
        ui.restartTarget()
        assertEquals(fact, database.call("r8-cancel-wait"))
        assertEquals(1, database.count("r8-cancel-wait"))
        record("R8_CANCEL_FACT=$fact")
    }

    private fun unknown(
        database: ReleaseDatabaseProbe,
        model: ReleaseModelFixture,
    ) {
        submit("unknown")
        await("real gesture entered", model) {
            ReleaseFixtureState.downs.get() == 1 &&
                database.call("r8-unknown-gesture").startsWith("RUNNING||RUNNING_TOOL|")
        }
        record("R8_UNKNOWN_BEFORE_STOP=${database.call("r8-unknown-gesture")};downs=${ReleaseFixtureState.downs.get()}")
        ui.restartTarget()
        await("durable interrupted effect", model) {
            database.call("r8-unknown-gesture").startsWith("INTERRUPTED||INTERRUPTED||")
        }
        val effectCount = ReleaseFixtureState.downs.get()
        val fact = database.call("r8-unknown-gesture")
        ui.restartTarget()
        SystemClock.sleep(1_500)
        assertEquals(fact, database.call("r8-unknown-gesture"))
        assertEquals(0, database.reviewCount("r8-unknown-gesture"))
        assertEquals(1, database.count("r8-unknown-gesture"))
        assertEquals(effectCount, ReleaseFixtureState.downs.get())
        record("R8_UNKNOWN_FACT=$fact;downs=$effectCount")
    }

    private fun stale(
        database: ReleaseDatabaseProbe,
        model: ReleaseModelFixture,
    ) {
        submit("stale-capture")
        await("real token captured", model) { model.oldToken != null }
        await("capture turn completed", model) {
            database.call("r8-stale-snapshot").startsWith("COMPLETED|SUCCEEDED|COMPLETED|")
        }
        ui.restartTarget()
        ui.await("R8_DONE_stale-capture")
        ui.reconnectOwnedAccessibility()
        submit("stale-replay")
        await("old token rejected and fresh token executed", model) {
            database.call("r8-stale-fresh").startsWith("COMPLETED|SUCCEEDED|")
        }
        val rejected = database.call("r8-stale-old")
        assertTrue("Old token must be unknown: $rejected", rejected.contains("TOKEN_UNKNOWN"))
        assertEquals(1, ReleaseFixtureState.clicks.get())
        assertEquals(1, database.count("r8-stale-old"))
        assertEquals(1, database.count("r8-stale-fresh"))
        record("R8_STALE_FACT=$rejected;freshClicks=${ReleaseFixtureState.clicks.get()}")
    }

    private fun record(value: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$value\n") })
    }

    private fun await(
        label: String,
        model: ReleaseModelFixture? = null,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(model?.failure?.get() == null) { "Fixture failure: ${model?.failure?.get()}" }
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label; UI=" + ui.nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
}
