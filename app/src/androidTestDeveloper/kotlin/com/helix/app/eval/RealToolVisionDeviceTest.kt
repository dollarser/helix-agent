package com.helix.app.eval

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnState
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in synthetic pixels through the actual model, Dispatcher and persisted image feedback. */
class RealToolVisionDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    private var tabId: String? = null
    private val browserCase get() = InstrumentationRegistry.getArguments().getString("visionBrowser") == "true"

    @Test
    fun realModelReadsToolPixels() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("realToolVision") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val service = container.providerService
            val existing = InstrumentationRegistry.getArguments().getString("visionProvider")
            val provider =
                existing ?: service.create(
                    ProviderDraft(
                        null,
                        "Synthetic vision acceptance",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://10.0.2.2:30008/v1"),
                        "Qwen3.8-27B",
                        "{}",
                        false,
                        CleartextAuthorization("10.0.2.2", 30008),
                        emptyList(),
                    ),
                    null,
                    cleartextConfirmed = true,
                )
            try {
                if (existing == null) {
                    check(service.runConnectionTest(provider) is ProbeOutcome.Ok)
                    service.runCapabilityTest(provider)
                    check(service.capabilitiesFor(provider)?.vision == true)
                }
                exerciseSession(app, provider)
            } finally {
                container.chatService.stop()
                container.chatService.closeSession()
                tabId?.let { id -> activity.scenario.onActivity { container.browser.closeTab(id) } }
                if (existing == null) service.delete(provider)
            }
        }

    private suspend fun exerciseSession(
        app: HelixApplication,
        provider: String,
    ) {
        val container = app.appContainer
        val session =
            container.chatService.createSession(
                "Synthetic vision acceptance",
                provider,
                InstrumentationRegistry.getArguments().getString("visionModel") ?: "Qwen3.8-27B",
            )
        val binding = requireNotNull(container.storage.workspaces.binding(session))
        val relative =
            listOf(
                binding.relativePath,
                "input/sample.png",
            ).filter { it.isNotBlank() }.joinToString("/")
        val file =
            container.storage.workspaces
                .managedDirectory(binding.workspaceId)
                .resolve(relative)
                .toFile()
        createImage(file)
        val reference = FileScopePath(binding.workspaceId, relative).toModelReference()
        container.chatService.openSession(session)
        container.chatService.setMode(AgentMode.ACT)
        if (browserCase) prepareBrowser(app)
        container.chatService.sendTestMessage(
            session,
            if (browserCase) {
                "Use browser.screenshot on tab $tabId, inspect the returned pixels, and reply only " +
                    "LEFT=<English color>;RIGHT=<English color>. Do not use DOM or infer colors from text."
            } else {
                "First call files.list with path \".\" to list this session Workspace. " +
                    "Then use view_image to inspect $reference. Identify the dominant color of each half. " +
                    "Reply only LEFT=<English color>;RIGHT=<English color>. Do not infer colors from the filename."
            },
        )
        val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            container.chatService.toolVisionConsent.pending.value.filter { it.sessionId == session }.forEach {
                container.chatService.toolVisionConsent.respond(it.id, true)
            }
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                approveScreenshot(app, turn.id)
                if (TurnState.valueOf(turn.state).isTerminal) break
            }
            delay(100)
        }
        verifyResult(app, session)
    }

    private suspend fun approveScreenshot(
        app: HelixApplication,
        turnId: String,
    ) {
        val container = app.appContainer
        container.storage.toolCalls.listByTurn(turnId).filter { it.state == "AWAITING_APPROVAL" }.forEach { call ->
            val approval = container.storage.approvals.byToolCall(call.callId)
            val args =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(call.argsJson)
                    .jsonObject
            if (approval != null && call.name == "browser.screenshot" &&
                args["tabId"]?.jsonPrimitive?.content == tabId
            ) {
                container.chatService.approveApproval(approval.id)
            }
        }
    }

    private fun verifyResult(
        app: HelixApplication,
        session: String,
    ) {
        val container = app.appContainer
        val turn =
            requireNotNull(
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull(),
            )
        val calls = container.storage.toolCalls.listByTurn(turn.id)
        val messages =
            container.storage.messages
                .listBySession(session)
                .filter { it.turnId == turn.id }
        val answer =
            messages
                .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                .mapNotNull { container.storage.messages.readContent(it) }
                .joinToString("\n")
        val imageCount =
            messages.sumOf { message ->
                container.storage.messageAttachments.listByMessage(message.id).count {
                    it.purpose ==
                        "TOOL_OBSERVATION"
                }
            }
        val visualTool = if (browserCase) "browser.screenshot" else "view_image"
        val passed =
            turn.state == "COMPLETED" && imageCount > 0 &&
                (browserCase || calls.any { it.name == "files.list" && it.state == "COMPLETED" }) &&
                calls.any { it.name == visualTool && it.state == "COMPLETED" } &&
                answer.contains("LEFT=red", true) && answer.contains("RIGHT=green", true)
        val result =
            buildJsonObject {
                put("passed", passed)
                put("turnState", turn.state)
                put("answer", answer)
                put("imageCount", imageCount)
                put("toolCalls", calls.joinToString { "${it.name}:${it.state}" })
            }
        File(app.filesDir, "real-tool-vision.json").writeText(result.toString())
        assertTrue(result.toString(), passed)
    }

    private suspend fun prepareBrowser(app: HelixApplication) {
        val controller = app.appContainer.browser
        activity.scenario.onActivity { owner ->
            val id = controller.newTab()
            tabId = id
            val html =
                "<html><body style='margin:0;" +
                    "background:linear-gradient(to right,red 50%,lime 50%)'></body></html>"
            controller.navigate(id, "data:text/html," + android.net.Uri.encode(html))
            owner.addContentView(requireNotNull(controller.hostView(id)), android.view.ViewGroup.LayoutParams(640, 320))
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val tab =
                controller.state.value.tabs
                    .first { it.id == tabId }
            if (!tab.isLoading && tab.navigationGeneration > 0) {
                delay(500)
                return
            }
            delay(100)
        }
        error("Synthetic browser page did not load")
    }

    private fun createImage(file: File) {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) bitmap.setPixel(x, y, if (x < 128) Color.RED else Color.GREEN)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
