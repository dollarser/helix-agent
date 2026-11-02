package com.helix.smoke

import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Public UI setup only. Exit instrumentation before the shell helper acquires UiAutomation. */
class BackgroundReleaseSetupTest {
    private val ui = ReleaseUiTestSupport()

    @Test fun requestSpecificallyApprovedShizukuGrant() {
        check(ui.context.packageName == "com.helix.validation.stability.developer")
        ui.ready()
        ui.activate("应用导航", "App navigation")
        ui.activate("设置", "Settings")
        ui.activate("权限与安全", "Permissions & safety")
        ui.activate("设置访问权限", "Manage access")
        ui.scrollTo("Shizuku 授权", "Shizuku authorization")
        var card = requireNotNull(ui.node("Shizuku 授权", "Shizuku authorization"))

        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }
        while (descendants(card).none { it.text?.toString() in setOf("去授权", "Authorize") }) {
            card = requireNotNull(card.parent)
        }
        ui.tap(descendants(card).first { it.text?.toString() in setOf("去授权", "Authorize") })
        // The host checks the manager's package and grants only the owner-approved isolated target.
    }

    @Test fun configureOwnedLoopbackConversation() {
        val endpoint = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureEndpoint"))
        require(endpoint.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+/v1")))
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        ui.ready()
        ui.activate("应用导航", "App navigation")
        if (ui.node("Standard") != null) {
            ui.activate("Standard")
            ui.activate("我已了解，确认切换", "I understand, switch")
        }
        ui.activate("模型", "Models")
        if (ui.node("Stability fixture") == null) {
            ui.activate("添加 API", "Add API")
            ui.scrollTo("自定义服务", "Custom service")
            ui.activate("自定义服务", "Custom service")
            ui.editField("Stability fixture", "名称", "Name")
            ui.editField(endpoint, "API 地址", "API endpoint")
            ui.scrollTo("＋ 添加模型", "+ Add model")
            ui.activate("＋ 添加模型", "+ Add model")
            ui.editField("stability-fixture", "模型名称（服务端标识）", "Model name (server ID)")
            ui.activate("保存", "Save")
        }
        ui.activate("连接测试", "Connection test")
        ui.activate("高级选项", "Advanced options")
        ui.scrollTo("能力检测", "Detect capabilities")
        ui.activate("能力检测", "Detect capabilities")
        ui.await("已通过 · 能力已探测", "Passed · capabilities detected", timeoutMillis = 30_000)
        ui.back()
        configureScope()
        ui.activate("应用导航", "App navigation")
        ui.activate("设置", "Settings")
        ui.activate("运行环境", "Execution environments")
        setChoice(true, "脚本调用工具", "Script tool delegation")
        ui.back()
        ui.activate("应用导航", "App navigation")
        ui.activate("当前会话", "Current conversation")
        if (ui.node("需要批准 ▾", "Approval required ▾") != null) {
            ui.activate("需要批准 ▾", "Approval required ▾")
            ui.activate("完全访问", "Full access")
        }
        ui.await("完全访问 ▾", "Full access ▾")
        if (ui.node("选择模型 ▾", "Select model ▾") != null) {
            ui.activate("选择模型 ▾", "Select model ▾")
            ui.activate("stability-fixture")
            ui.activate("保存", "Save")
        }
        ui.await("stability-fixture ▾")
        if (ui.node("执行", "Act") == null) {
            ui.enterText("/act")
            ui.click("发送", "Send")
            ui.await("执行", "Act")
        }
        if (InstrumentationRegistry.getArguments().getString("prepareOnly") == "true") return
        ui.enterText("STABILITY_SYNTHETIC Only operate the disposable fixture.", focus = true)
        ui.click("发送", "Send")
        // The host gates provider responses until this instrumentation exits. No API hidden disconnect.
    }

    @Test fun submitPreparedOwnedConversation() {
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        ui.ready()
        ui.activate("应用导航", "App navigation")
        ui.activate("当前会话", "Current conversation")
        ui.await("stability-fixture ▾")
        ui.await("执行", "Act")
        ui.enterText("STABILITY_SYNTHETIC Only operate the disposable fixture.", focus = true)
        ui.click("发送", "Send")
    }

    private fun configureScope() {
        ui.activate("应用导航", "App navigation")
        ui.activate("插件与专家", "Plugins & experts")
        ui.enterText("mobile-use")
        ui.activate("Mobile Use")
        ui.activate("插件设置", "Plugin settings")
        if (ui.node("执行屏幕", "Execution display") == null) ui.activate("高级配置", "Advanced configuration")
        setChoice(
            false,
            "授权本次操作全手机（包括系统界面）",
            "Authorize the whole phone for this session, including system UI",
        )
        val selectionLabel = Regex("""选择应用（已选 [0-9]+ 个）|Choose apps \([0-9]+ selected\)""")
        val choose =
            ui
                .nodes()
                .first {
                    it.text?.toString()?.matches(selectionLabel) == true
                }.text
                .toString()
        ui.activate(choose)
        val fixture = InstrumentationRegistry.getInstrumentation().context.packageName
        ui.enterText(fixture)
        if (!ui.checkedChoice(fixture)) ui.activate(fixture)
        check(ui.checkedChoice(fixture))
        ui.activate("确认选择", "Confirm selection")
        val backgroundLabels =
            arrayOf(
                "使用后台屏幕（不勾选时使用主屏）",
                "Use a background display (otherwise use the main screen)",
            )
        setChoice(true, *backgroundLabels)
        ui.scrollTo("保存插件设置", "Save plugin settings")
        var save = ui.await("保存插件设置", "Save plugin settings")
        while (!save.isClickable) save = requireNotNull(save.parent)
        if (save.isEnabled) ui.activate("保存插件设置", "Save plugin settings") else ui.back()
        ui.await("在会话中使用", "Use in a conversation")
        ui.scrollTo("在会话中使用", "Use in a conversation")
        ui.activate("在会话中使用", "Use in a conversation")
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        val hint =
            arrayOf(
                "启用 Mobile Use，让 Helix 按已设置的范围操作手机。",
                "Enable Mobile Use to control the phone within its configured scope.",
            )
        val active = arrayOf("本会话已启用手机操作", "Phone control is enabled for this conversation")
        while (ui.node(*hint) == null && ui.node(*active) == null &&
            android.os.SystemClock.elapsedRealtime() < deadline
        ) {
            android.os.SystemClock.sleep(100)
        }
        if (ui.node(*hint) != null) {
            ui.activate("在本会话启用", "Enable for this conversation")
            ui.await("应用导航", "App navigation")
        } else {
            ui.await(*active)
            ui.activate("关闭", "Close")
        }
    }

    private fun setChoice(
        value: Boolean,
        vararg labels: String,
    ) {
        ui.scrollTo(*labels)
        val anchor = Rect().also(requireNotNull(ui.node(*labels))::getBoundsInScreen)
        val choice =
            ui.nodes().filter { it.isCheckable }.minBy {
                kotlin.math.abs(Rect().also(it::getBoundsInScreen).centerY() - anchor.centerY())
            }
        check(choice.isEnabled)
        if (choice.isChecked != value) check(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        android.os.SystemClock.sleep(300)
    }
}
