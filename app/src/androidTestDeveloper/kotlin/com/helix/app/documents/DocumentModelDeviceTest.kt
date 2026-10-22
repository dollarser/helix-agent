package com.helix.app.documents

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.openTestSessionControl
import com.helix.app.provider.ProviderDraft
import com.helix.app.sendTestMessage
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit keyless local model, generated documents, independent answers and original-byte checks. */
class DocumentModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test fun pdfSummaryUsesBothPages() = summarize("pdf")

    @Test fun docxSummaryUsesParagraphsAndTable() = summarize("docx")

    @Suppress("LongMethod") // One owned provider/session; cleanup joins physical execution before deleting its fixture.
    private fun summarize(format: String) =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.containsKey("documentModelPort"))
            val port = requireNotNull(args.getString("documentModelPort")).toInt()
            require(port in 1..65535)
            val model = requireNotNull(args.getString("documentModel"))
            require(model.isNotBlank())
            val previous = c.runControlStore.current
            val previousProfile = c.profileStore.profile
            val originalSession = c.chatService.screen.value.openSessionId
            val provider =
                c.providerService.create(
                    ProviderDraft(
                        "generic-openai",
                        "Document fixture",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                        model,
                        "{}",
                        false,
                    ),
                    null,
                )
            var cleanupSession: String? = null
            try {
                check(c.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                val session = c.chatService.createSession("Document $format fixture", provider, model)
                cleanupSession = session
                c.profileStore.switchTo(SafetyProfile.ADVANCED)
                c.chatService.setReasoning(ReasoningEffort.OFF)
                val binding = requireNotNull(c.storage.workspaces.binding(session))
                val root =
                    c.storage.workspaces
                        .managedDirectory(binding.workspaceId)
                        .resolve(binding.relativePath)
                        .toFile()
                val source = File(root, "input/fixture.$format").apply { parentFile!!.mkdirs() }
                if (format == "pdf") writePdf(source) else writeDocx(source)
                val original = source.readBytes()
                c.pluginService.selectFromUser(session, "bundled-plugin:documents", true)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                c.chatService.openTestSessionControl(session, AgentMode.ACT, TurnBudgets(maxOutputTokens = 8192))
                c.chatService.sendTestMessage(
                    session,
                    "Use the documents plugin to read every page/block of input/fixture.$format. " +
                        "Report the document's verification code, completed Beijing total, completed Shanghai total, " +
                        "and verification status. Cancelled orders do not count. " +
                        "Reply in exactly code|BeijingTotal|ShanghaiTotal|status format. " +
                        "Do not modify files or operate other apps. Do not infer unread content.",
                )
                withTimeout(240_000) {
                    while (c.storage.turns.listBySession(session).lastOrNull()?.let {
                            TurnState.valueOf(it.state).isTerminal
                        } != true
                    ) {
                        // Owner opted into this model and these generated documents. A PDF on
                        // API < 35 sends pixels, so acknowledge only this fixture's disclosure.
                        c.chatService.toolVisionConsent.pending.value.filter { it.sessionId == session }.forEach {
                            c.chatService.toolVisionConsent.respond(it.id, true)
                        }
                        delay(100)
                    }
                }
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .last()
                val calls = c.storage.toolCalls.listByTurn(turn.id)
                val answer =
                    c.storage.messages
                        .listBySession(session)
                        .filter { it.turnId == turn.id && it.role == "ASSISTANT" && it.kind == "TEXT" }
                        .joinToString("\n") {
                            c.storage.messages
                                .readContent(it)
                                .orEmpty()
                        }
                val usage =
                    c.storage.modelCalls
                        .listByTurn(turn.id)
                        .map { it.usage }
                println(
                    "DOCUMENT_MODEL format=$format state=${turn.state} error=${turn.errorCode} " +
                        "calls=${calls.map { it.name to it.state }} usage=$usage",
                )
                assertEquals(turn.errorCode, TurnState.COMPLETED.name, turn.state)
                assertTrue(
                    "No real document inspection",
                    calls.any { it.name == "documents.inspect" && it.state == "COMPLETED" },
                )
                assertDocumentRead(format, calls)
                assertTrue("Incorrect summary: $answer", answer.replace(" ", "").contains("ORANGE-42|42|9|PENDING"))
                assertTrue("Source changed", original.contentEquals(source.readBytes()))
                println("DOCUMENT_MODEL=PASS format=$format originalUnchanged=true independentAnswer=true")
            } finally {
                cleanupSession?.let { session ->
                    c.storage.turns.listBySession(session).forEach {
                        c.chatService.stopTurn(it.id)
                        withTimeout(20_000) { c.agentRuntime.observe(TurnId(it.id)).collect {} }
                    }
                    c.chatService.closeSession()
                    c.storage.deleteSessionPermanently(session)
                }
                c.providerService.delete(provider)
                c.profileStore.switchTo(previousProfile)
                c.runControlStore.setMode(previous.mode)
                c.runControlStore.setReasoning(previous.reasoning)
                c.runControlStore.setBudgets(previous.budgets)
                originalSession?.let(c.chatService::openSession)
            }
        }

    private fun assertDocumentRead(
        format: String,
        calls: List<ToolCallEntity>,
    ) {
        if (format == "pdf" && Build.VERSION.SDK_INT < 35) {
            val viewedPages =
                calls.filter { it.name == "documents.view" && it.state == "COMPLETED" }.map {
                    Json
                        .parseToJsonElement(it.argsJson)
                        .jsonObject
                        .getValue("page")
                        .jsonPrimitive.int
                }
            assertTrue("Both PDF pages must be viewed on API < 35: $viewedPages", viewedPages.containsAll(listOf(1, 2)))
        } else {
            assertTrue(
                "No real document read",
                calls.any { it.name == "documents.read" && it.state == "COMPLETED" },
            )
        }
    }

    private fun writePdf(file: File) {
        val document = PdfDocument()
        try {
            listOf(
                listOf("City,Amount,Status", "Beijing,18,completed", "Shanghai,9,completed"),
                listOf("Beijing,24,completed", "Shanghai,17,cancelled", "Code: ORANGE-42", "Verification: PENDING"),
            ).forEachIndexed { index, lines ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 500, index + 1).create())
                lines.forEachIndexed { row, text ->
                    page.canvas.drawText(text, 20f, 40f + row * 35f, Paint().apply { textSize = 18f })
                }
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun writeDocx(file: File) {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val rows =
            listOf(
                "City,Amount,Status",
                "Beijing,18,completed",
                "Shanghai,9,completed",
                "Beijing,24,completed",
                "Shanghai,17,cancelled",
            )
        val table =
            rows.joinToString("") { row ->
                val cells = row.split(',').joinToString("") { "<w:tc><w:p><w:r><w:t>$it</w:t></w:r></w:p></w:tc>" }
                "<w:tr>$cells</w:tr>"
            }
        val xml =
            "<w:document xmlns:w=\"$w\"><w:body>" +
                "<w:p><w:r><w:t>Code: ORANGE-42. Verification: PENDING. 中文文档测试。</w:t></w:r></w:p>" +
                "<w:tbl>$table</w:tbl></w:body></w:document>"
        val packaging = "http://schemas.openxmlformats.org/package/2006"
        val relationships = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val parts =
            mapOf(
                "word/document.xml" to xml,
                "[Content_Types].xml" to "<Types xmlns=\"$packaging/content-types\">" +
                    "<Override PartName=\"/word/document.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument." +
                    "wordprocessingml.document.main+xml\"/>" +
                    "</Types>",
                "_rels/.rels" to "<Relationships xmlns=\"$packaging/relationships\">" +
                    "<Relationship Id=\"main\" Type=\"$relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "</Relationships>",
            )
        ZipOutputStream(file.outputStream()).use { zip ->
            parts.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
