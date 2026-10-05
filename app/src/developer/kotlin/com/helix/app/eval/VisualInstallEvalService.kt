package com.helix.app.eval

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import com.helix.app.HelixApplication
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

class VisualInstallEvalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || intent?.getBooleanExtra(ARG_ENABLED, false) != true) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread({ runEvaluation(startId) }, "visual-install-eval").start()
        return START_NOT_STICKY
    }

    private fun runEvaluation(startId: Int) {
        val evidenceFile = File(filesDir, EVIDENCE_FILE)
        runCatching {
            runBlocking {
                val app = application as HelixApplication
                val container = app.appContainer
                val selection = MobileUseEvaluationSelection(app)
                val previousProfile = container.profileStore.profile
                var provider: String? = null
                var session: String? = null
                try {
                    check(!isInstalled()) { "fixture requires WeChat to be uninstalled" }
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    provider = container.providerService.create(providerDraft(), null)
                    check(container.providerService.runConnectionTest(provider, MODEL) is ProbeOutcome.Ok)
                    val capability = container.providerService.runCapabilityTest(provider, MODEL)
                    check(capability is ProbeOutcome.Ok && capability.capabilities.vision) {
                        "vision capability was not proved: $capability"
                    }

                    session = container.chatService.createSession("Visual install eval", provider, MODEL)
                    container.sessionPermissionEdit.saveSessionConfig(
                        session,
                        SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                        System.currentTimeMillis(),
                    )
                    selection.select(session, emptySet(), wholePhone = true)
                    container.chatService.openSession(session)
                    container.chatService.setMode(AgentMode.ACT)
                    waitUntil(10_000) {
                        com.helix.app.automation.AutomationModule
                            .scopeFor("ui.snapshot", session) != null
                    }
                    submitAndAwait(app, session)
                    writeEvidence(app, session, evidenceFile)
                } finally {
                    container.chatService.stop()
                    container.chatService.closeSession()
                    selection.close()
                    provider?.let { container.providerService.delete(it) }
                    container.profileStore.switchTo(previousProfile)
                }
            }
        }.onFailure { failure ->
            evidenceFile.writeText(
                buildJsonObject {
                    put("status", "FAIL")
                    put("error", failure.javaClass.simpleName + ": " + failure.message.orEmpty())
                    put("installed", isInstalled())
                }.toString(),
            )
        }
        stopSelf(startId)
    }

    private fun providerDraft() =
        ProviderDraft(
            null,
            "Visual install eval",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            NormalizedEndpoint.parse("http://10.0.2.2:30008/v1"),
            MODEL,
            "{}",
            false,
            CleartextWarning("10.0.2.2", 30008),
            emptyList(),
        )

    private suspend fun submitAndAwait(
        app: HelixApplication,
        session: String,
    ) {
        val chat = app.appContainer.chatService
        val receipt =
            chat
                .sendSubmission(
                    ChatSubmission(
                        session,
                        0,
                        "visual-install-eval-${System.nanoTime()}",
                        "The current foreground screen is the Android installer for WeChat. " +
                            "Use ui.device and ui.screenshot to inspect the current frame visually. " +
                            "Click the visible Install button using ui.gesture on that SAME frame. " +
                            "Do not use ui.find, ui.wait, ui.click, or ui.click_match. " +
                            "After the gesture, observe the result and finish only after installation completes.",
                    ),
                ).await()
        check(receipt.outcome is ChatSubmissionOutcome.Accepted || receipt.outcome is ChatSubmissionOutcome.Enqueued) {
            "visual install submission rejected: ${receipt.outcome}"
        }
        waitUntil(120_000) {
            app.appContainer.storage.turns
                .listBySession(session)
                .lastOrNull()
                ?.let { TurnState.valueOf(it.state).isTerminal } == true
        }
        waitUntil(60_000) { isInstalled() }
    }

    private fun writeEvidence(
        app: HelixApplication,
        session: String,
        evidenceFile: File,
    ) {
        val storage = app.appContainer.storage
        val turn = requireNotNull(storage.turns.listBySession(session).lastOrNull())
        val calls = storage.toolCalls.listByTurn(turn.id)
        val screenshots =
            calls.filter { it.name == "ui.screenshot" }.mapNotNull { call ->
                storage.toolResults.byToolCall(call.id)?.let(storage.toolResults::readContent)
            }
        check(turn.state == TurnState.COMPLETED.name) { "turn did not complete: ${turn.state}" }
        check(calls.any { it.name == "ui.gesture" }) { "ui.gesture was not used" }
        check(calls.none { it.name in SEMANTIC_TOOLS }) { "semantic tools participated: $calls" }
        check(screenshots.any { it.contains("\"pixelsAttached\":true") }) { "screenshot pixels were not delivered" }
        evidenceFile.writeText(
            buildJsonObject {
                put("status", "DIAGNOSTIC_ONLY")
                put("vision", true)
                put("installed", isInstalled())
                put("turnState", turn.state)
                put("calls", JsonArray(calls.map { JsonPrimitive(it.name) }))
                put("gestureCount", calls.count { it.name == "ui.gesture" })
                put("semanticActionCount", calls.count { it.name in SEMANTIC_TOOLS })
                put("pixelsAttached", true)
            }.toString(),
        )
    }

    private fun isInstalled(): Boolean =
        try {
            packageManager.getPackageInfo(WECHAT_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
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
        const val EVIDENCE_FILE = "visual-install-eval.json"
        const val ARG_ENABLED = "helixPackageInstallerVisualFallback"
        const val MODEL = "Qwen3.8-27B"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        val SEMANTIC_TOOLS = setOf("ui.find", "ui.wait", "ui.click", "ui.click_match")
    }
}
