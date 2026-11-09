package com.helix.app.eval

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.StartupOnboarding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Nonbinding startup UI only, within one preserving instrumentation lifetime. */
class AndroidWorldOnboardingSetupDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()

    private data class NativeNode(
        val value: AccessibilityNodeInfo,
        val parent: Int,
        val bounds: Rect,
    )

    private class Screen(
        val nodes: List<NativeNode>,
    ) : AutoCloseable {
        override fun close() = nodes.asReversed().forEach { it.value.recycle() }
    }

    @Test
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun acknowledgeNonbindingStartup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("helix.authorizedOnboardingSetup") == "true")
        val runId = requireNotNull(args.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,120}")))
        val expectedAvd = requireNotNull(args.getString("avdName"))
        require(expectedAvd.matches(Regex("Helix_Benchmark_[A-Za-z0-9_]+")))
        val folder = File(app.filesDir, "androidworld-onboarding/$runId")
        check(!folder.exists()) { "Never overwrite onboarding evidence" }
        folder.mkdirs()
        val events = mutableListOf<JsonObject>()
        val observations = mutableListOf<String>()
        var status = "BLOCKED"
        var failure: String? = null
        var activeTurnsEmpty = false
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val started = SystemClock.elapsedRealtime()
        try {
            val actualAvd =
                ui.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
                    java.io.FileInputStream(it.fileDescriptor).use { stream -> String(stream.readBytes()).trim() }
                }
            check(actualAvd == expectedAvd) { "Only the designated isolated benchmark AVD is permitted" }
            activeTurnsEmpty =
                app.appContainer.storage.turns
                    .listActive()
                    .isEmpty()
            check(activeTurnsEmpty) { "No onboarding during model execution" }
            val info = ui.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            ui.serviceInfo = info
            instrumentation.runOnMainSync {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val clicked = mutableSetOf<String>()
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                check(
                    app.appContainer.storage.turns
                        .listActive()
                        .isEmpty(),
                ) { "Unexpected active model turn" }
                if (advanceStartup(ui, folder, observations, events, clicked)) status = "READY"
                if (status == "READY") break
                SystemClock.sleep(500)
            }
            check(status == "READY") { "Known Helix navigation was not verified within 60 seconds" }
        } catch (error: Exception) {
            failure = "${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            File(folder, "result.json").writeText(
                buildJsonObject {
                    put("runId", runId)
                    put("avdName", expectedAvd)
                    put("status", status)
                    put("error", failure)
                    put("activeTurnsEmpty", activeTurnsEmpty)
                    put("permissionsGranted", false)
                    put("taskActions", 0)
                    put("modelActions", 0)
                    put("normalUiClicksOnly", true)
                    put("navigationVerified", status == "READY")
                    put("onboardingRemaining", app.startupOnboarding.value != StartupOnboarding.COMPLETE)
                    put("cleanupErrors", JsonArray(emptyList()))
                    put("readySnapshot", observations.lastOrNull())
                    put("navigationLabel", app.getString(com.helix.ui.shared.R.string.chat_app_navigation))
                    put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    put("events", JsonArray(events))
                    put("observations", JsonArray(observations.map(::JsonPrimitive)))
                }.toString(),
            )
        }
    }

    private fun advanceStartup(
        ui: UiAutomation,
        folder: File,
        observations: MutableList<String>,
        events: MutableList<JsonObject>,
        clicked: MutableSet<String>,
    ): Boolean {
        capture(ui, folder, observations)?.use { first ->
            val state = classify(first)
            if (state == "READY") {
                check(clicked.all { it in setOf("NOTICE", "PERMISSIONS") })
                return true
            }
            if (state in setOf("NOTICE", "PERMISSIONS") && state !in clicked) {
                clickStartupState(ui, folder, observations, events, clicked, first, state)
            }
        }
        return false
    }

    private fun clickStartupState(
        ui: UiAutomation,
        folder: File,
        observations: MutableList<String>,
        events: MutableList<JsonObject>,
        clicked: MutableSet<String>,
        first: Screen,
        state: String,
    ) {
        val labelId =
            if (state == "NOTICE") {
                R.string.notice_continue
            } else {
                R.string.startup_permissions_continue
            }
        val label = app.getString(labelId)
        val original = target(first, label)
        // Fresh tree and exact same native state/bounds immediately before normal UI click.
        capture(ui, folder, observations)?.use { fresh ->
            check(classify(fresh) == state) { "Native onboarding state changed before click" }
            val button = target(fresh, label)
            check(button.bounds == original.bounds) { "Onboarding bounds changed before click" }
            clicked += state
            val event =
                buildJsonObject {
                    put("state", state)
                    put("label", label)
                    put("snapshot", observations.last())
                    put("clickAttempted", true)
                }
            events += event
            check(button.value.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                "Native onboarding click did not dispatch; never retry this state"
            }
        }
    }

    private fun capture(
        ui: UiAutomation,
        folder: File,
        observations: MutableList<String>,
    ): Screen? {
        val root = ui.rootInActiveWindow ?: return null
        val nodes = mutableListOf<NativeNode>()

        fun visit(
            value: AccessibilityNodeInfo,
            parent: Int,
            depth: Int,
        ) {
            check(nodes.size < 5000 && depth < 100) { "Native tree exceeds bounded observation" }
            val bounds = Rect()
            value.getBoundsInScreen(bounds)
            val index = nodes.size
            nodes += NativeNode(value, parent, bounds)
            for (child in 0 until value.childCount) value.getChild(child)?.let { visit(it, index, depth + 1) }
        }
        try {
            visit(root, -1, 0)
            val name = "screen-${observations.size}.json"
            File(folder, name).writeText(
                buildJsonObject {
                    put("runId", folder.name)
                    put("package", root.packageName?.toString())
                    put("windowId", root.windowId)
                    put("onboardingState", app.startupOnboarding.value?.name)
                    put(
                        "nodes",
                        JsonArray(
                            nodes.map { node ->
                                buildJsonObject {
                                    put("text", node.value.text?.toString())
                                    put("contentDescription", node.value.contentDescription?.toString())
                                    put("viewId", node.value.viewIdResourceName)
                                    put("package", node.value.packageName?.toString())
                                    put("windowId", node.value.windowId)
                                    put("class", node.value.className?.toString())
                                    put("enabled", node.value.isEnabled)
                                    put("clickable", node.value.isClickable)
                                    put("visible", node.value.isVisibleToUser)
                                    put("parent", node.parent)
                                    put("bounds", node.bounds.flattenToString())
                                }
                            },
                        ),
                    )
                }.toString(),
            )
            observations += name
            return Screen(nodes)
        } catch (error: Exception) {
            nodes.asReversed().forEach { it.value.recycle() }
            throw error
        }
    }

    private fun classify(screen: Screen): String {
        if (screen.nodes
                .first()
                .value.packageName
                ?.toString() != app.packageName
        ) {
            return "UNKNOWN"
        }
        val texts = screen.nodes.filter { it.value.isVisibleToUser }.mapNotNull { it.value.text?.toString() }

        fun has(id: Int) = app.getString(id) in texts
        return when {
            has(R.string.notice_title) -> {
                classifyNotice(texts)
            }

            has(R.string.startup_permissions_title) -> {
                val complete = has(R.string.startup_permissions_help) && has(R.string.startup_permissions_continue)
                if (complete) "PERMISSIONS" else "UNKNOWN"
            }

            else -> {
                classifyNavigation(screen)
            }
        }
    }

    private fun classifyNotice(texts: List<String>): String {
        val sections =
            listOf(
                R.string.notice_section_1,
                R.string.notice_section_2,
                R.string.notice_section_3,
                R.string.notice_section_4,
                R.string.notice_continue,
            )
        return if (sections.all { app.getString(it) in texts }) "NOTICE" else "UNKNOWN"
    }

    private fun classifyNavigation(screen: Screen): String {
        val navigationLabel = app.getString(com.helix.ui.shared.R.string.chat_app_navigation)
        val navigation =
            screen.nodes.any {
                it.value.isVisibleToUser && it.value.contentDescription?.toString() == navigationLabel
            }
        val completed = app.startupOnboarding.value == StartupOnboarding.COMPLETE
        return if (completed && navigation) "READY" else "UNKNOWN"
    }

    private fun target(
        screen: Screen,
        label: String,
    ): NativeNode {
        var index =
            screen.nodes.indices.single { index ->
                val value = screen.nodes[index].value
                val samePackage = value.packageName?.toString() == app.packageName
                samePackage && value.isVisibleToUser && value.text?.toString() == label
            }
        while (!screen.nodes[index].value.isClickable) {
            index = screen.nodes[index].parent
            check(index >= 0) { "No native clickable parent for exact label" }
        }
        return screen.nodes[index].also {
            check(
                it.value.packageName?.toString() == app.packageName && it.value.isEnabled &&
                    it.value.isVisibleToUser && !it.bounds.isEmpty,
            ) { "Onboarding target is not a live Helix control" }
        }
    }
}
