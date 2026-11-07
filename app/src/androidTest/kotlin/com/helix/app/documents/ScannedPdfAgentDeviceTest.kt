package com.helix.app.documents

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.provider.BuiltInModel
import com.helix.core.model.AgentMode
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Explicit opt-in: real production ChatService, Dispatcher, image disclosure and account model. */
class ScannedPdfAgentDeviceTest {
    @Test
    @Suppress("LongMethod") // One bounded live turn owns its isolated workspace and unconditional cleanup.
    fun agentChoosesAndReadsRasterPages(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("helix.scannedPdfAgent")
            org.junit.Assume.assumeTrue("Explicit real-model opt-in absent", flag != null)
            require(flag == "true")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer")
            app.account.restore()
            check(app.account.signedIn.value) { "Existing account must be signed in" }
            val c = app.awaitAppContainer()
            val chat = c.chatService
            check(
                c.turnEngine
                    ?.liveExecution
                    ?.activeTurns()
                    .isNullOrEmpty(),
            ) { "Another turn is active" }
            val previous = chat.screen.value.openSessionId
            val session =
                chat.createSession(
                    "Synthetic scanned PDF acceptance",
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val code = "SYNTH-${(100000..999999).random()}"
            val factoryField = c.providerService.javaClass.getDeclaredField("factory")
            factoryField.isAccessible = true
            val factory = factoryField.get(c.providerService)
            val field = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
            val original = field.get(factory) as BuiltInAccountWire
            val observer = RasterAgentWire(original.newInvocation(), code)
            var scope: String? = null
            try {
                chat.openSession(session)
                val privateSkills = c.pluginService.standaloneSkills(session)
                privateSkills.filter { it.enabled }.forEach { skill ->
                    c.pluginService.selectSkill(skill.key, session, false)
                }
                check(c.pluginService.standaloneSkills(session).none { it.enabled })
                c.conversationExperts.selectExpert(session, null).await()
                val binding = requireNotNull(c.storage.workspaces.binding(session))
                scope = binding.workspaceId
                val root = c.storage.workspaces.managedDirectory(binding.workspaceId)
                val file = root.resolve("synthetic-scanned-report.pdf").toFile()
                rasterReport(file, code)
                val rules =
                    OperationEffect.entries.associateWith {
                        if (it == OperationEffect.FILE_READ_WORKSPACE) OperationRule.ALLOW else OperationRule.DENY
                    }
                val permissionConfig = SessionPermissionConfig.custom(rules)
                c.sessionPermissionEdit.saveSessionConfig(session, permissionConfig, System.currentTimeMillis())
                val rows = c.pluginService.sessionRows(session)
                val documents =
                    c.pluginService
                        .list()
                        .single { it.native?.pluginId == "documents" }
                        .id
                val expected = rows.associate { it.id to it.selected }
                c.pluginService.applySessionSelection(session, expected, setOf(documents))
                chat.setMode(AgentMode.CHAT)
                chat.setChatToolsEnabled(true)
                chat.setTurnBudgets(TurnBudgets(2048))
                delay(300)
                field.set(factory, observer)
                val request =
                    ChatSubmission(
                        session,
                        0,
                        UUID.randomUUID().toString(),
                        "Inspect synthetic-scanned-report.pdf in this workspace. " +
                            "Find the lighthouse inspection code. " +
                            "Use document tools and read the actual page image " +
                            "if text extraction is unavailable. " +
                            "Return the exact code and cite the filename " +
                            "and page number. Do not guess.",
                    )
                val first = chat.sendSubmission(request).await().outcome
                val accepted =
                    if (first is ChatSubmissionOutcome.PendingConfirmation) {
                        val confirmed = chat.confirmSubmission(request).await().outcome
                        confirmed as ChatSubmissionOutcome.Accepted
                    } else {
                        first as ChatSubmissionOutcome.Accepted
                    }
                withTimeout(150_000) {
                    while (!TurnState
                            .valueOf(
                                c.storage.turns
                                    .resolve(accepted.turnId)
                                    .state,
                            ).isTerminal
                    ) {
                        c.toolVisionConsent.pending.value
                            .filter { it.sessionId == session && it.turnId == accepted.turnId }
                            .forEach { c.toolVisionConsent.respond(it.id, true) }
                        delay(100)
                    }
                }
                val calls = c.storage.toolCalls.listByTurn(accepted.turnId)
                val answer =
                    c.storage.messages
                        .byTurn(accepted.turnId)
                        .filter { it.role == "ASSISTANT" && it.kind == "TEXT" }
                        .joinToString("\n") {
                            c.storage.messages
                                .readContent(it)
                                .orEmpty()
                        }
                val viewed = calls.any { it.name == "documents.view" && it.state == "COMPLETED" }
                val pagePattern = Regex("(?i)(page|页|p\\.)\\s*[:：]?\\s*2|第\\s*2\\s*页")
                val pageCited = pagePattern.containsMatchIn(answer)
                File(app.cacheDir, "scanned-pdf-agent-result.json").writeText(
                    buildJsonObject {
                        put("model", BuiltInModel.MODEL_ID)
                        put(
                            "state",
                            c.storage.turns
                                .resolve(accepted.turnId)
                                .state,
                        )
                        put("requests", observer.requests)
                        put("firstRequestHasNoAnswerOrImage", observer.firstClean)
                        put("imageConsumedByLaterRequest", observer.imageSeen)
                        put("documentViewCompleted", viewed)
                        put("randomCodeCorrect", code in answer)
                        put("filenameCited", "synthetic-scanned-report.pdf" in answer)
                        put("pageTwoCited", pageCited)
                        put("toolNames", calls.joinToString(",") { it.name })
                    }.toString(),
                )
                assertEquals(
                    "COMPLETED",
                    c.storage.turns
                        .resolve(accepted.turnId)
                        .state,
                )
                assertTrue("Initial prompt must not contain the answer or image", observer.firstClean)
                assertTrue("A rendered image must reach a later real model request", observer.imageSeen)
                assertTrue("The Agent must actually call documents.view", viewed)
                assertTrue("The model must read the unpredictable raster code", code in answer)
                assertTrue("The answer must cite the source filename", "synthetic-scanned-report.pdf" in answer)
                assertTrue("The answer must cite page two", pageCited)
            } finally {
                withContext(NonCancellable) {
                    c.storage.turns
                        .listBySession(session)
                        .forEach { chat.stopTurn(it.id) }
                    withTimeout(30_000) { while (c.turnEngine?.liveExecution?.active(session) != null) delay(100) }
                    field.set(factory, original)
                    chat.preparePermanentDeletion(session)
                    c.storage.deleteSessionPermanently(session)
                    scope?.let { c.fileManager.cleanupWorkspace(it) }
                    if (previous != null) chat.openSession(previous) else chat.closeSession()
                }
            }
        }

    private fun rasterReport(
        file: File,
        code: String,
    ) {
        val pdf = PdfDocument()
        try {
            listOf(
                listOf("COASTAL REPORT", "Contents", "Lighthouse observations: page 2"),
                listOf("LIGHTHOUSE RECORD", "Station: PORT HELIOS", "Inspection code: $code"),
            ).forEachIndexed {
                index,
                lines,
                ->
                val image = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                canvas.drawColor(Color.WHITE)
                val paint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 36f
                    }
                lines.forEachIndexed { row, text -> canvas.drawText(text, 48f, 100f + row * 100, paint) }
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(1000, 700, index + 1).create())
                page.canvas.drawBitmap(image, 0f, 0f, null)
                pdf.finishPage(page)
                image.recycle()
            }
            file.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
    }
}

private class RasterAgentWire(
    private val original: WireClient,
    private val code: String,
) : WireClient {
    var requests = 0
        private set
    var firstClean = false
        private set
    var imageSeen = false
        private set

    override suspend fun open(request: WireRequest): WireResponse {
        if (request.method == "POST" && request.url == BuiltInModel.GENERATION_URL) {
            if (++requests > 8) throw IOException("Synthetic PDF request budget exhausted")
            val body = requireNotNull(request.body).toString(Charsets.UTF_8)
            if (requests == 1) firstClean = code !in body && "data:image/" !in body
            imageSeen = imageSeen || "data:image/" in body
        }
        return original.open(request)
    }
}
