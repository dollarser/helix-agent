package com.helix.app.plugin

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.agent.ChatHistoryBuilder
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
    private val ownedBackend =
        com.helix.app.eval
            .OwnedAccessibilityBackend(app)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun workflowGuide() = matrix("workflow")

    @Test fun browserButton() = matrix("browser")

    @Test fun fileSummary() = matrix("files")

    @Test fun mobileSyntheticForm() = matrix("mobile")

    @Test fun mobileOffscreenFind() = matrix("mobile", findBeforeScroll = true)

    @Test
    fun returnedFrameWaitsForForegroundLifecycle() =
        runBlocking {
            c.firstLaunch.markSeen()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            repeat(6) {
                app.startActivity(
                    Intent()
                        .setComponent(
                            ComponentName("${app.packageName}.test", AutomationEvaluationActivity::class.java.name),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                await { automation.rootInActiveWindow?.packageName?.toString() == "${app.packageName}.test" }
                returnToHelix()
                awaitMainFrame()
                await { automation.rootInActiveWindow?.packageName?.toString() == app.packageName }
            }
        }

    /** Keep one provider and UI owner for a continuous workload; every pair still verifies actual business results. */
    fun mobileContinuous(afterPair: (Int) -> Boolean) = matrix("mobile", afterPair)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Fixed three tasks share owned setup and restoration.
    private fun matrix(
        kind: String,
        afterPair: ((Int) -> Boolean)? = null,
        findBeforeScroll: Boolean = false,
    ) =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val providerProfile = args.getString("progressiveProfile") ?: "qwen"
            require(providerProfile in setOf("qwen", "codex"))
            val backend = args.getString("pluginSkillBackend") ?: "accessibility"
            require(backend in setOf("accessibility", "root", "shizuku"))
            require(backend == "accessibility" || kind == "mobile")
            val visual = args.getString("pluginSkillVisual") == "true"
            require(!visual || kind == "mobile")
            val postObservation = args.getString("pluginSkillPostObservation") == "true"
            require(!postObservation || kind == "mobile")
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
                            "generic-openai",
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
                        var fileInput: java.io.File? = null
                        var fileOutput: java.io.File? = null
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
                                    "files" -> {
                                        val binding = requireNotNull(c.storage.workspaces.binding(session))
                                        val root =
                                            c.storage.workspaces
                                                .managedDirectory(binding.workspaceId)
                                                .resolve(binding.relativePath)
                                                .toFile()
                                        fileInput =
                                            java.io.File(root, "input/orders.csv").apply {
                                                parentFile!!.mkdirs()
                                                writeText(FILE_SOURCE)
                                            }
                                        fileOutput = java.io.File(root, "output/result.txt")
                                        "读取本会话 input/orders.csv，按城市汇总状态为完成的金额，取消订单不计。" +
                                            "将每个城市结果按北京、上海顺序写为恰好两行“城市,金额”，" +
                                            "保存到 output/result.txt，不加表头、总计或说明，读回核验。" +
                                            "不要修改原文件，不操作手机或其他应用。"
                                    }

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
                                            ownedBackend.connect()
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
                                            (
                                                if (findBeforeScroll) {
                                                    "Locate 完成测试 using ui.find before scrolling. "
                                                } else {
                                                    ""
                                                }
                                            ) +
                                            "Scroll to 完成测试, click once; verify success. Stay there." +
                                            (
                                                if (postObservation) {
                                                    " After ui.set_text/ui.scroll, read observation when " +
                                                        "observationStatus=ATTACHED. " +
                                                        "Use its fresh tokens for the next action; " +
                                                        "query again only when needed."
                                                } else {
                                                    ""
                                                }
                                            ) +
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
                            await {
                                c.storage.turns
                                    .listBySession(session)
                                    .isNotEmpty()
                            }
                            val requestedTurnId =
                                c.storage.turns
                                    .listBySession(session)
                                    .first()
                                    .id
                            await(240000) {
                                c.storage.turns.find(requestedTurnId)?.let {
                                    TurnState.valueOf(it.state).isTerminal
                                } == true
                            }
                            val turn = requireNotNull(c.storage.turns.find(requestedTurnId))
                            val calls = c.storage.toolCalls.listByTurn(turn.id)
                            val models = c.storage.modelCalls.listByTurn(turn.id)
                            verifyCanonicalResponseProjection(session, repetition)
                            val verified =
                                when (kind) {
                                    "files" -> {
                                        fileInput?.readText() == FILE_SOURCE &&
                                            fileOutput
                                                ?.takeIf { it.exists() }
                                                ?.readLines()
                                                ?.filter { it.isNotBlank() }
                                                ?.map(String::trim) ==
                                            listOf("北京,42", "上海,9")
                                    }

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
                            val offscreenFind =
                                calls.indexOfFirst { call ->
                                    call.name == "ui.find" && call.state == "COMPLETED" &&
                                        c.storage.toolResults.byToolCall(call.id)?.let {
                                            c.storage.toolResults
                                                .readContent(it)
                                                ?.contains("\"offscreen\":true")
                                        } == true
                                }
                            val offscreenRecovered =
                                offscreenFind >= 0 &&
                                    calls.drop(offscreenFind + 1).any {
                                        it.name == "ui.scroll" &&
                                            it.state == "COMPLETED"
                                    }
                            val success =
                                turn.state == "COMPLETED" && verified && calls.isNotEmpty() &&
                                    (!findBeforeScroll || offscreenRecovered)
                            if (postObservation) {
                                val attached =
                                    calls.filter { it.name in setOf("ui.set_text", "ui.scroll") }.count { call ->
                                        c.storage.toolResults
                                            .byToolCall(call.id)
                                            ?.let(c.storage.toolResults::readContent)
                                            ?.contains("\"observationStatus\":\"ATTACHED\"") == true
                                    }
                                println("POST_ACTION_OBSERVATION rep=$repetition attached=$attached")
                                assertTrue("No actual post-action observation returned", attached > 0)
                            }
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
                                    "offscreenRequired=$findBeforeScroll offscreenRecovered=$offscreenRecovered " +
                                    "error=${turn.errorCode} elapsed=${SystemClock.elapsedRealtime() - start} " +
                                    "calls=${calls.map { it.name to it.state }} usage=${models.map { it.usage }}",
                            )
                            println(
                                "PROGRESSIVE_RELATED_TURNS rep=$repetition requested=$requestedTurnId " +
                                    "states=${c.storage.turns.listBySession(
                                        session,
                                    ).map { it.id to (it.state to it.errorCode) }}",
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
                            c.storage.turns.listBySession(session).asReversed().forEach {
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
                ownedBackend.close()
                c.profileStore.switchTo(profile)
                if (providerProfile == "qwen") c.providerService.delete(provider)
                c.runControlStore.setMode(controls.mode)
                c.runControlStore.setReasoning(controls.reasoning)
                c.runControlStore.setBudgets(controls.budgets)
            }
        }

    private companion object {
        const val FILE_SOURCE = "城市,金额,状态\n北京,18,完成\n上海,9,完成\n北京,24,完成\n上海,17,取消\n"
    }

    private fun verifyCanonicalResponseProjection(
        session: String,
        repetition: Int,
    ) {
        val rows = c.storage.messages.listBySession(session)
        val persisted =
            rows.map { row ->
                ChatHistoryBuilder.PersistedRow(
                    row.turnId,
                    row.role,
                    row.kind,
                    c.storage.messages.readContent(row),
                    row.id,
                )
            }
        val messages = ChatHistoryBuilder.toModelMessagesStrict(persisted)
        val steps = rows.filter { it.kind == ChatHistoryBuilder.KIND_TOOL_CALLS }
        val progress = rows.filter { it.kind == ChatHistoryBuilder.KIND_TOOL_PROGRESS }
        org.junit.Assert.assertEquals(steps.size, messages.count { it.toolCalls.isNotEmpty() })
        progress.forEach { display ->
            val step = rows.getOrNull(rows.indexOf(display) + 1)
            assertTrue(step?.kind == ChatHistoryBuilder.KIND_TOOL_CALLS && step.turnId == display.turnId)
            val index = rows.indexOf(requireNotNull(step))
            val message = ChatHistoryBuilder.toModelMessagesStrict(listOf(persisted[index])).single()
            org.junit.Assert.assertEquals(c.storage.messages.readContent(display), message.text)
            assertTrue(message.toolCalls.isNotEmpty())
        }
        println(
            "CANONICAL_RESPONSE_PROJECTION rep=$repetition responses=${steps.size}" +
                " progress=${progress.size}",
        )
    }

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
        val deadline = SystemClock.elapsedRealtime() + 5_000
        try {
            while (observer == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val activities =
                        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                            .getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<MainActivity>()
                    assertTrue("Multiple resumed Helix activities", activities.size <= 1)
                    activities.singleOrNull()?.let { activity ->
                        val decor = activity.window.decorView
                        if (decor.isAttachedToWindow && decor.isShown) {
                            assertTrue("Returned Helix window must use hardware rendering", decor.isHardwareAccelerated)
                            observer = decor.viewTreeObserver
                            observer!!.registerFrameCommitCallback(callback)
                            decor.invalidate()
                        }
                    }
                }
                if (observer == null) Thread.sleep(20)
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            assertTrue("Helix did not resume and attach within the frame deadline", observer != null && remaining > 0)
            committed.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
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

    private suspend fun await(
        timeout: Long = 15000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) delay(200)
        check(condition()) { "Fixture deadline $timeout ms" }
    }
}
