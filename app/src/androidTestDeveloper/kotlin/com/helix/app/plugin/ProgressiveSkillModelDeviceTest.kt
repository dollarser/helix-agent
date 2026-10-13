package com.helix.app.plugin

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.eval.AutomationEvaluationActivity
import com.helix.app.eval.MobileUseEvaluationSelection
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.deviceaccess.DeviceAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed two repetitions per task and independent outcome checks; frozen APKs supply each strategy. */
@Suppress("TooManyFunctions") // Three task journeys share one owner and cleanup; helper count is not a behavior limit.
class ProgressiveSkillModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun workflowGuide() = matrix("workflow")

    @Test fun browserButton() = matrix("browser")

    @Test fun mobileSyntheticForm() = matrix("mobile")

    /** Keep one provider and UI owner for a continuous workload; every pair still verifies actual business results. */
    fun mobileContinuous(afterPair: (Int) -> Boolean) = matrix("mobile", afterPair)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Fixed three tasks share owned setup and restoration.
    private fun matrix(kind: String, afterPair: ((Int) -> Boolean)? = null) =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val providerProfile = args.getString("progressiveProfile") ?: "qwen"
            require(providerProfile in setOf("qwen", "codex"))
            val backend = args.getString("pluginSkillBackend") ?: "accessibility"
            require(backend in setOf("accessibility", "root", "shizuku"))
            require(backend == "accessibility" || kind == "mobile")
            val visual = args.getString("pluginSkillVisual") == "true"
            require(!visual || kind == "mobile")
            val port =
                if (providerProfile ==
                    "qwen"
                ) {
                    requireNotNull(args.getString("pluginSkillPort")).toInt()
                } else {
                    null
                }
            val model = requireNotNull(args.getString("pluginSkillModel"))
            val provider =
                if (providerProfile == "codex") {
                    com.helix.app.provider.SubscriptionProviderModule.CODEX_ID
                } else {
                    c.providerService.create(
                        ProviderDraft(
                            null,
                            "Progressive fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                            model,
                            "{}",
                            false,
                            CleartextWarning("127.0.0.1", requireNotNull(port)),
                            emptyList(),
                        ),
                        null,
                    )
                }
            val profile = c.profileStore.profile
            val controls = c.runControlStore.current
            val outcomes = mutableListOf<Boolean>()
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                c.firstLaunch.markSeen()
                try {
                    returnToHelix()
                    var repetition = 0
                    do {
                        val session = c.chatService.createSession("Progressive $kind $repetition", provider, model)
                        var tab: String? = null
                        val selection = MobileUseEvaluationSelection(app)
                        try {
                            c.sessionPermissionEdit.saveSessionConfig(
                                session,
                                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                                System.currentTimeMillis(),
                            )
                            c.chatService.openTestSessionControl(
                                session,
                                AgentMode.ACT,
                                TurnBudgets(24, 24, 524288, 8192, 557056),
                            )
                            val prompt =
                                when (kind) {
                                    "workflow" -> {
                                        c.pluginService.selectFromUser(session, "bundled-plugin:chat-reply", true)
                                        "Read chat-reply using skills.list then skills.read with its exact hash. " +
                                            "Use only this synthetic message: 'Can we meet at 3pm?'. " +
                                            "Draft exactly 'Yes, see you at 3pm.' for review. " +
                                            "Do not inspect phone, send messages or write files."
                                    }

                                    "browser" -> {
                                        instrumentation.runOnMainSync {
                                            tab =
                                                c.browser
                                                    .openTab(
                                                        "data:text/html,<html><body>" +
                                                            "<button onclick=\"this.dataset.count=" +
                                                            "Number(this.dataset.count||0)+1;" +
                                                            "this.textContent='VERIFIED'\">" +
                                                            "Verify fixture</button>" +
                                                            "</body></html>",
                                                    ).tabId
                                        }
                                        await {
                                            var loaded = false
                                            instrumentation.runOnMainSync {
                                                loaded =
                                                    c.browser.tab(requireNotNull(tab))?.isLoading == false
                                            }
                                            loaded
                                        }
                                        "Operate only synthetic browser tab ${requireNotNull(tab)}. " +
                                            "Click Verify fixture once; verify VERIFIED. " +
                                            "Do not navigate or use phone actions."
                                    }

                                    else -> {
                                        if (backend == "root") {
                                            DeviceAccess.requestRootFromUser("mobile-use")
                                            await { DeviceAccess.root("mobile-use")?.cachedAppGrant == true }
                                            DeviceAccess.connectRoot("mobile-use")
                                            await { DeviceAccess.root("mobile-use")?.connectedBinder() != null }
                                        } else if (backend == "accessibility") {
                                            enableBackend()
                                        }
                                        val packages = setOf(app.packageName, "${app.packageName}.test")
                                        check(selection.select(session, packages, false))
                                        app.startActivity(
                                            Intent()
                                                .setComponent(
                                                    ComponentName(
                                                        "${app.packageName}.test",
                                                        AutomationEvaluationActivity::class.java.name,
                                                    ),
                                                ).addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                                                ).putExtra("modelJourney", true),
                                        )
                                        delay(800)
                                        "Operate only this test app and Helix. Fill 测试输入 with 你好 Helix, " +
                                            "Scroll to 完成测试, click once; verify success. Stay there." +
                                            if (visual) {
                                                " For the final click, use ui.device then ui.screenshot. " +
                                                    "Locate 完成测试 from the attached pixels and click using " +
                                                    "ui.gesture with that screenshot's frame. Do not guess coordinates."
                                            } else {
                                                ""
                                            }
                                    }
                                }
                            val start = SystemClock.elapsedRealtime()
                            c.chatService.sendTestMessage(session, prompt)
                            await(240000) {
                                c.storage.turns.listBySession(session).lastOrNull()?.let {
                                    TurnState.valueOf(it.state).isTerminal
                                } == true
                            }
                            val turn =
                                c.storage.turns
                                    .listBySession(session)
                                    .last()
                            val calls = c.storage.toolCalls.listByTurn(turn.id)
                            val models = c.storage.modelCalls.listByTurn(turn.id)
                            val verified =
                                when (kind) {
                                    "workflow" -> {
                                        val reply =
                                            c.storage.messages
                                                .listBySession(session)
                                                .lastOrNull { it.role == "ASSISTANT" && it.kind == "TEXT" }
                                                ?.let(c.storage.messages::readContent)
                                                .orEmpty()
                                        calls.any { it.name == "skills.read" && it.state == "COMPLETED" } &&
                                            calls.none {
                                                it.name.startsWith("ui.") || it.name == "chat.reply" ||
                                                    it.name == "write" || it.name == "file.write"
                                            } &&
                                            reply.contains("Yes, see you at 3pm.")
                                    }

                                    "browser" -> {
                                        val text = java.util.concurrent.CompletableFuture<String>()
                                        instrumentation.runOnMainSync {
                                            checkNotNull(c.browser.hostView(requireNotNull(tab)))
                                                .evaluateJavascript(
                                                    "document.querySelector('button').textContent+':'" +
                                                        "+document.querySelector('button').dataset.count",
                                                ) { value ->
                                                    text.complete(value)
                                                }
                                        }
                                        calls.any { it.name == "browser.click" && it.state == "COMPLETED" } &&
                                            text.get(5, java.util.concurrent.TimeUnit.SECONDS) == "\"VERIFIED:1\""
                                    }

                                    else -> {
                                        app.contentResolver
                                            .query(
                                                android.net.Uri.parse(
                                                    "content://${app.packageName}.test.capability-state/result",
                                                ),
                                                null,
                                                null,
                                                null,
                                                null,
                                            )!!
                                            .use { cursor ->
                                                val result =
                                                    cursor.moveToFirst() &&
                                                        cursor.getString(0) == "TEST_DONE:你好 Helix"
                                                result &&
                                                    cursor.getInt(1) == 1
                                            }
                                    }
                                }
                            val success = turn.state == "COMPLETED" && verified && calls.isNotEmpty()
                            if (visual) {
                                assertTrue(calls.any { it.name == "ui.gesture" && it.state == "COMPLETED" })
                                assertTrue(
                                    calls.any { call ->
                                        call.name == "ui.screenshot" && call.state == "COMPLETED" &&
                                            c.storage.toolResults.byToolCall(call.id)?.let {
                                                c.storage.toolResults
                                                    .readContent(
                                                        it,
                                                    )?.contains("\"pixelsAttached\":true")
                                            } == true
                                    },
                                )
                            }
                            calls.filter { !success || it.state != "COMPLETED" }.forEach { call ->
                                val result = c.storage.toolResults.byToolCall(call.id)
                                println(
                                    "PROGRESSIVE_FAILURE kind=$kind rep=$repetition name=${call.name} " +
                                        "args=${call.argsJson} result=${result?.summary} " +
                                        "body=${result?.let(c.storage.toolResults::readContent)?.take(1800)}",
                                )
                            }
                            println(
                                "PROGRESSIVE profile=$providerProfile kind=$kind rep=$repetition success=$success " +
                                    "state=${turn.state} " +
                                    "error=${turn.errorCode} elapsed=${SystemClock.elapsedRealtime() - start} " +
                                    "calls=${calls.map { it.name to it.state }} usage=${models.map { it.usage }}",
                            )
                            if (kind == "mobile" && success && backend == "accessibility") {
                                verifyMobileBackgroundReturn(session, repetition)
                            }
                            if (backend != "accessibility") {
                                assertTrue(
                                    "The real task must use the requested privileged backend",
                                    calls.any { call ->
                                        c.storage.toolResults
                                            .byToolCall(
                                                call.id,
                                            )?.let(c.storage.toolResults::readContent)
                                            ?.contains("\"backend\":\"$backend\"") == true
                                    },
                                )
                            }
                            outcomes.add(success)
                            // Keep every business result authoritative, but complete the bounded resource
                            // measurement before the final all-results assertion; failures never become passes.
                        } finally {
                            c.storage.turns
                                .listBySession(session)
                                .lastOrNull()
                                ?.let {
                                    c.chatService.stopTurn(it.id)
                                    withTimeout(20_000) { c.agentRuntime.observe(TurnId(it.id)).collect {} }
                                    assertTrue(
                                        TurnState.valueOf(requireNotNull(c.storage.turns.find(it.id)).state).isTerminal,
                                    )
                                }
                            selection.close()
                            tab?.let { id -> instrumentation.runOnMainSync { c.browser.closeTab(id) } }
                            c.chatService.closeSession()
                            c.storage.deleteSessionPermanently(session)
                            returnToHelix()
                            com.helix.app.diagnostics
                                .recordMobileDescriptors("task-$repetition-cleaned")
                            if (afterPair != null) {
                                awaitMainFrame()
                                com.helix.app.diagnostics
                                    .recordMobileDescriptors("task-$repetition-frame-committed")
                            }
                        }
                        repetition++
                    } while (if (afterPair ==
                            null
                        ) {
                            repetition < 2
                        } else {
                            repetition % 2 != 0 || afterPair(repetition / 2)
                        }
                    )
                } finally {
                    returnToHelix()
                }
                if (afterPair == null) assertEquals("Fixed task repetitions", 2, outcomes.size)
                assertTrue("Task results: $outcomes", outcomes.all { it })
            } finally {
                if (backend == "root") DeviceAccess.disconnectRoot("mobile-use")
                restoreBackend()
                c.profileStore.switchTo(profile)
                if (providerProfile == "qwen") c.providerService.delete(provider)
                c.runControlStore.setMode(controls.mode)
                c.runControlStore.setReasoning(controls.reasoning)
                c.runControlStore.setBudgets(controls.budgets)
            }
        }

    private var previousServices: String? = null
    private var previousEnabled = 0
    private var backendChanged = false
    private val accessibilityEvents = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Keep the original selected session and live Accessibility backend across actual HOME/return. */
    private suspend fun verifyMobileBackgroundReturn(
        session: String,
        repetition: Int,
    ) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val homePackage = requireNotNull(app.packageManager.resolveActivity(home, 0)).activityInfo.packageName
        repeat(2) { cycle ->
            com.helix.app.diagnostics
                .recordMobileDescriptors("return-$repetition-$cycle-before-home")
            app.startActivity(home)
            await { automation.rootInActiveWindow?.packageName?.toString() == homePackage }
            returnToHelix()
            await { automation.rootInActiveWindow?.packageName?.toString() == app.packageName }
            await { visibleTextBounds(automation.rootInActiveWindow, "Progressive mobile $repetition") != null }
            delay(500)
            com.helix.app.diagnostics
                .recordMobileDescriptors("return-$repetition-$cycle-before-screenshot")
            val pixels = requireNotNull(automation.takeScreenshot())
            try {
                com.helix.app.diagnostics
                    .recordMobileDescriptors("return-$repetition-$cycle-after-screenshot")
                val titleBounds =
                    requireNotNull(
                        visibleTextBounds(automation.rootInActiveWindow, "Progressive mobile $repetition"),
                    )
                val name = "mobile-return-$repetition-$cycle.png"
                app.filesDir.resolve(name).outputStream().use {
                    pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue(
                    "Expected conversation title has no rendered contrast",
                    titlePixelsRendered(pixels, titleBounds),
                )
                assertEquals(session, c.chatService.screen.value.openSessionId)
                assertEquals(AutomationServiceState.CONNECTED, AutomationPermissionCenter(app).serviceState())
                println(
                    "MOBILE_BACKGROUND_RETURN rep=$repetition cycle=$cycle titlePixels=true sessionRetained=true",
                )
            } finally {
                pixels.recycle()
                com.helix.app.diagnostics
                    .recordMobileDescriptors("return-$repetition-$cycle-after-recycle")
            }
        }
    }

    private fun awaitMainFrame() {
        val committed = java.util.concurrent.CompletableFuture<Unit>()
        val callback = Runnable { committed.complete(Unit) }
        var observer: android.view.ViewTreeObserver? = null
        instrumentation.runOnMainSync {
            val activity =
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .single()
            val decor = activity.window.decorView
            assertTrue("Returned Helix window must use hardware rendering", decor.isHardwareAccelerated)
            observer = decor.viewTreeObserver
            observer!!.registerFrameCommitCallback(callback)
            decor.invalidate()
        }
        try {
            committed.get(5, java.util.concurrent.TimeUnit.SECONDS)
            // Resource snapshots must follow the same settled foreground lifecycle at every pair.
            // One fixed settling interval (as in the app resource fixture), never FD-based retry or GC.
            Thread.sleep(1_000)
        } finally {
            instrumentation.runOnMainSync { observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback) }
        }
    }

    private fun returnToHelix() {
        app.startActivity(
            Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }

    @Suppress("DEPRECATION", "ReturnCount") // Return the first visible match and recycle each owned child.
    private fun visibleTextBounds(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        expected: String,
    ): android.graphics.Rect? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == expected) {
            return android.graphics
                .Rect()
                .also(node::getBoundsInScreen)
                .takeUnless { it.isEmpty }
        }
        return (0 until node.childCount).firstNotNullOfOrNull { index ->
            node.getChild(index)?.let { child ->
                try {
                    visibleTextBounds(child, expected)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private suspend fun enableBackend() {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.setOnAccessibilityEventListener { event ->
            val pkg = event.packageName?.toString()
            if (pkg == app.packageName || pkg == "${app.packageName}.test") {
                accessibilityEvents.merge("$pkg:${event.windowId}:${event.eventType}", 1, Int::plus)
            }
        }
        if (!backendChanged) {
            previousServices = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
            previousEnabled = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
            backendChanged = true
        }
        val component = "${app.packageName}/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
        if (component in previousServices.orEmpty().split(':') &&
            AutomationPermissionCenter(app).serviceState() != AutomationServiceState.CONNECTED
        ) {
            // Instrumentation can force-stop the target while its enabled-service setting remains.
            // Rewriting the same list does not clear the system's crashed-service state.
            val others = previousServices.orEmpty().split(':').filter { it.isNotBlank() && it != component }
            shellOutput(
                automation,
                if (others.isEmpty()) {
                    "settings delete secure enabled_accessibility_services"
                } else {
                    "settings put secure enabled_accessibility_services ${others.joinToString(":")}"
                },
            )
            await {
                val states =
                    shellOutput(automation, "dumpsys accessibility")
                        .lineSequence()
                        .map(String::trim)
                        .filter {
                            it.startsWith("Enabled services:") || it.startsWith("Crashed services:")
                        }.toList()
                states.size >= 2 && states.none { component in it }
            }
            println("MOBILE_BACKEND_REBIND acknowledged=true")
        }
        val services =
            previousServices
                .orEmpty()
                .split(':')
                .filter(String::isNotBlank)
                .toSet() + component
        val command = "settings put secure enabled_accessibility_services ${services.joinToString(":")}"
        shellOutput(automation, command)
        shellOutput(automation, "settings put secure accessibility_enabled 1")
        await { AutomationPermissionCenter(app).serviceState() == AutomationServiceState.CONNECTED }
    }

    private fun shellOutput(
        automation: UiAutomation,
        command: String,
    ): String =
        android.os.ParcelFileDescriptor
            .AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun restoreBackend() {
        if (!backendChanged) return
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        println("MOBILE_ACCESSIBILITY_EVENTS=$accessibilityEvents")
        automation.setOnAccessibilityEventListener(null)
        automation
            .executeShellCommand(
                if (previousServices.isNullOrBlank()) {
                    "settings delete secure enabled_accessibility_services"
                } else {
                    "settings put secure enabled_accessibility_services $previousServices"
                },
            ).close()
        automation.executeShellCommand("settings put secure accessibility_enabled $previousEnabled").close()
    }

    private suspend fun await(
        timeout: Long = 15000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(200)
        check(condition()) { "Fixture deadline $timeout ms" }
    }
}
