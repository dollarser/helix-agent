package com.helix.app.documents

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.BuiltInModel
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** One natural OCR task over synthetic pixels; no local OCR engine, private document or forced tool step. */
class ModelOcrDeviceTest {
    @Test
    @Suppress("LongMethod") // Bounded model observation and unconditional owned-session cleanup.
    fun visualModelReadsImageOnlyPdfThroughExistingDocumentTools(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("modelOcrAcceptance")
            assumeTrue(flag != null)
            require(flag == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            require(app.packageName.endsWith(".developer.asrvalidation"))
            val c = app.awaitAppContainer()
            check(app.account.signedIn.value)
            val session = c.chatService.createSession("Synthetic OCR", BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
            var active: String? = null
            try {
                val binding = requireNotNull(c.storage.workspaces.binding(session))
                val root =
                    c.storage.workspaces
                        .managedDirectory(binding.workspaceId)
                        .resolve(binding.relativePath)
                        .toFile()
                val source = File(root, "input/scanned.pdf").apply { parentFile!!.mkdirs() }
                writeScan(source)
                val original = source.readBytes()
                c.pluginService.selectFromUser(session, "bundled-plugin:documents", true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                    System.currentTimeMillis(),
                )
                active =
                    c.agentRuntime
                        .submit(
                            SubmitTurnCommand(
                                SessionId(session),
                                ProviderId(BuiltInModel.PROVIDER_ID),
                                AgentMode.ACT,
                                "请识别 input/scanned.pdf 扫描页上的凭证码和总金额，回答凭证码|总金额。不要修改文件或推测未查看的内容。",
                                TurnBudgets(2048),
                                clientRequestId = UUID.randomUUID().toString(),
                                directUserRequest = true,
                            ),
                        ).value
                val turnId = requireNotNull(active)
                withTimeout(120_000) {
                    while (!TurnState
                            .valueOf(
                                c.storage.turns
                                    .resolve(turnId)
                                    .state,
                            ).isTerminal
                    ) {
                        check(
                            c.storage.modelCalls
                                .listByTurn(turnId)
                                .size <= 6,
                        ) { "Bounded OCR model-call budget exceeded" }
                        c.chatService.toolVisionConsent.pending.value.filter { it.sessionId == session }.forEach {
                            c.chatService.toolVisionConsent.respond(it.id, true)
                        }
                        delay(100)
                    }
                }
                val turn = c.storage.turns.resolve(turnId)
                val calls = c.storage.toolCalls.listByTurn(turnId)
                val answer =
                    c.storage.messages
                        .listBySession(session)
                        .filter { it.turnId == turnId && it.role == "ASSISTANT" && it.kind == "TEXT" }
                        .joinToString("\n") {
                            c.storage.messages
                                .readContent(it)
                                .orEmpty()
                        }
                File(app.cacheDir, "model-ocr-result.txt").writeText(
                    "state=${turn.state}\ncalls=${calls.map { it.name to it.state }}\nanswer=$answer\n",
                )
                assertTrue(
                    "Model did not view original scanned pixels",
                    calls.any { it.name == "documents.view" && it.state == "COMPLETED" },
                )
                assertTrue("OCR answer incorrect", answer.contains("OCR-782") && answer.contains("47"))
                assertTrue(original.contentEquals(source.readBytes()))
            } finally {
                active?.let { id ->
                    c.chatService.stopTurn(id)
                    withTimeout(20_000) { c.agentRuntime.observe(TurnId(id)).collect {} }
                }
                c.storage.deleteSessionPermanently(session)
            }
        }

    private fun writeScan(file: File) {
        val bitmap = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 40f
                }
            canvas.drawText("CODE: OCR-782", 30f, 80f, paint)
            canvas.drawText("TOTAL: 47", 30f, 160f, paint)
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(640, 240, 1).create())
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(page)
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
