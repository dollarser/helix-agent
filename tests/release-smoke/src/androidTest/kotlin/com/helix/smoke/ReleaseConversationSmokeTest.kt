package com.helix.smoke

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Public UI -> real R8 ChatService/AgentLoop/Room -> HTTP, tool backfill, cancellation and restart. */
class ReleaseConversationSmokeTest {
    private val ui = ReleaseUiTestSupport()

    @Test fun conversationToolBackfillAndCancellationSurviveProcessRestart() {
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val scenario = ReleaseConversationScenario()
        ReleaseModelFixture(InstrumentationRegistry.getInstrumentation().context.packageName, scenario).use { model ->
            configure(model)
            submit(scenario, "plain")
            await(model) { scenario.count("plain") == 1 }
            ui.await(scenario.answer("plain"), timeoutMillis = 30_000)
            await(model) { ui.node("停止", "Stop") == null }
            assertEquals(1, scenario.count("plain"))
            submit(scenario, "tool")
            ui.await(scenario.answer("tool"), timeoutMillis = 30_000)
            await(model) { ui.node("停止", "Stop") == null }
            assertEquals(2, scenario.count("tool"))
            assertEquals(1, scenario.toolBackfills.get())
            ui.restartTarget()
            ui.await(scenario.answer("tool"))
            assertEquals(2, scenario.count("tool"))
            submit(scenario, "continue")
            ui.await(scenario.answer("continue"), timeoutMillis = 30_000)
            await(model) { ui.node("停止", "Stop") == null }
            assertEquals(1, scenario.restoredHistories.get())
            submit(scenario, "cancel")
            await(model) { scenario.count("cancel") == 1 }
            ui.activate("停止", "Stop")
            await(model) { scenario.disconnected.get() == 1 && ui.node("停止", "Stop") == null }
            ui.restartTarget()
            SystemClock.sleep(1_000)
            assertEquals(1, scenario.count("cancel"))
            assertNull(ui.node(scenario.answer("cancel")))
            submit(scenario, "aftercancel")
            ui.await(scenario.answer("aftercancel"), timeoutMillis = 30_000)
            await(model) { ui.node("停止", "Stop") == null }
            assertEquals(2, scenario.restoredHistories.get())
            assertEquals(1, scenario.count("cancel"))
            assertEquals(1, scenario.count("plain"))
            assertEquals(2, scenario.count("tool"))
            check(model.failure.get() == null) { "Loopback fixture failed: ${model.failure.get()}" }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "stream",
                        "R8_CORE=ordinary,tool-backfill,persisted-history,cancel,no-replay,continue;passed\n",
                    )
                },
            )
        }
    }

    private fun configure(model: ReleaseModelFixture) {
        ui.ready()
        ui.activate("应用导航", "App navigation")
        ui.activate("模型", "Models")
        if (ui.node("添加 API", "Add API") == null) ui.activate("API 服务", "API services", "API")
        ui.activate("添加 API", "Add API")
        ui.scrollTo("自定义服务", "Custom service")
        ui.activate("自定义服务", "Custom service")
        ui.editField("R8 core fixture", "名称", "Name")
        ui.scrollTo("API 地址", "API address")
        ui.editField(model.endpoint, "API 地址", "API address")
        ui.scrollTo("模型名称（服务端标识）", "Model name (server identifier)")
        ui.editField("r8-fixture", "模型名称（服务端标识）", "Model name (server identifier)")
        ui.activate("保存", "Save")
        ui.await("R8 core fixture")
        ui.scrollTo("连接测试", "Connection test")
        ui.activate("连接测试", "Connection test")
        ui.await("模型目录已读取", "Model directory retrieved", timeoutMillis = 30_000)
        ui.scrollTo("r8-fixture")
        ui.activate("r8-fixture")
        ui.scrollTo("能力检测", "Detect capabilities")
        ui.activate("能力检测", "Detect capabilities")
        await(model) {
            ui.nodes().any { node ->
                val text = node.text?.toString().orEmpty()
                text.startsWith("工具调用 ✓") || text.startsWith("Tool calls ✓")
            }
        }
        ui.activate("应用导航", "App navigation")
        ui.activate("当前会话", "Current conversation")
        ui.activate("新建会话", "New session")
        if (ui.node("选择模型 ▾", "Choose model ▾") != null) {
            ui.activate("选择模型 ▾", "Choose model ▾")
            ui.activate("r8-fixture")
            ui.activate("保存", "Save")
        }
        ui.await("r8-fixture ▾")
    }

    private fun submit(
        scenario: ReleaseConversationScenario,
        phase: String,
    ) {
        ui.enterText(scenario.prompt(phase), focus = true)
        ui.activate("发送", "Send")
    }

    private fun await(
        model: ReleaseModelFixture,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(model.failure.get() == null) { "Fixture failure: ${model.failure.get()}" }
            if (condition()) return
            SystemClock.sleep(100)
        }
        error(
            "Core conversation did not settle; UI=" +
                ui.nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "),
        )
    }
}
