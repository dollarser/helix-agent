package com.helix.app.knowledge

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.citation.Citation
import com.helix.app.ui.CitationPreviewDialog
import com.helix.app.ui.LocalCitationHandler
import com.helix.app.ui.MarkdownText
import com.helix.core.storage.content.DocumentStorage
import com.helix.core.storage.knowledge.CollectedSource
import com.helix.core.storage.knowledge.KnowledgeStore
import com.helix.tools.android.HttpFetchBridge
import com.helix.tools.android.HttpFetchOutcome
import com.helix.tools.android.HttpFetchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Actual isolated platform PDF + actual link click, in memory-only owned documents; no account or model calls. */
class KnowledgePdfDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Suppress("LongMethod") // One owned native-PDF-to-click journey, with deterministic cleanup.
    fun importedPageCoverageAndCitationUseTheActualPdfPage() =
        runBlocking {
            assumeTrue(Build.VERSION.SDK_INT >= 35)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val documents = KnowledgeFixtureDocuments()
            val store = KnowledgeStore(documents, KnowledgeFixtureDocuments())
            val service = KnowledgeService(context, fetcher = NoPdfNetwork(), store = store)
            try {
                val bytes = pdf()
                val original =
                    documents.save(
                        "originals/source",
                        "synthetic.pdf",
                        bytes,
                        mimeType = "application/pdf",
                    )
                val files =
                    JSONArray().put(
                        JSONObject()
                            .put("reference", original.reference)
                            .put("name", "Synthetic PDF")
                            .put("mime", "application/pdf"),
                    )
                val source =
                    store.insert(
                        CollectedSource(
                            "source",
                            "Synthetic PDF evidence",
                            1,
                            "fixture",
                            "",
                            "",
                            filesJson = files.toString(),
                            state = "QUEUED",
                        ),
                    )
                withContext(Dispatchers.IO) {
                    KnowledgeExtractor(store, NoPdfNetwork(), KnowledgePdfExtraction(context)).extract(source)
                }
                val extracted = requireNotNull(store.get("source"))
                val coverage =
                    Json
                        .parseToJsonElement(extracted.extraction)
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(3, coverage.getValue("totalPages").jsonPrimitive.int)
                assertEquals(3, coverage.getValue("processedPages").jsonPrimitive.int)
                val blankPage = coverage.getValue("pages").jsonArray[2].jsonObject
                assertEquals("no_text", blankPage.getValue("state").jsonPrimitive.content)
                assertEquals("INCOMPLETE", extracted.state)
                assertTrue(extracted.extracted.contains("PAGE_2_UNIQUE_EVIDENCE"))
                val reference = service.references.select("SOURCE", "source")
                val url =
                    "helix://knowledge/source/source?revision=${reference.sourceHash}" +
                        "&range=0:22&file=0&page=2"
                val citation = requireNotNull(Citation.parse(url))
                val preview = requireNotNull(service.resolveCitation(citation))
                assertTrue(preview.excerpt.contains("PAGE_2_UNIQUE_EVIDENCE"))
                val open = mutableStateOf<Citation?>(null)
                compose.setContent {
                    androidx.compose.material3.MaterialTheme {
                        CompositionLocalProvider(
                            LocalCitationHandler provides { value -> open.value = Citation.parse(value) },
                        ) {
                            MarkdownText("[资料证据]($url)")
                            open.value?.let {
                                CitationPreviewDialog(it, service::resolveCitation) { open.value = null }
                            }
                        }
                    }
                }
                compose.onNodeWithText("资料证据").performTouchInput { click(center) }
                compose.waitUntil(15_000) {
                    compose
                        .onAllNodes(
                            androidx.compose.ui.test
                                .hasText("PAGE_2_UNIQUE_EVIDENCE", substring = true),
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose.onNodeWithTag("citation-preview").assertIsDisplayed()
                store.save(extracted.copy(extracted = "Changed extraction"))
                assertNull(service.resolveCitation(citation))
                store.remove("source")
                assertNull(service.resolveCitation(citation))
                assertEquals(DocumentStorage.hash(bytes), documents.read(original.reference, 1_000_000).hash)
            } finally {
                service.close()
            }
        }

    private fun pdf(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val document = PdfDocument()
        try {
            for (number in 1..3) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 500, number).create())
                if (number < 3) {
                    page.canvas.drawText(
                        "PAGE_${number}_UNIQUE_EVIDENCE",
                        20f,
                        60f,
                        Paint().apply { textSize = 16f },
                    )
                }
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
        return output.toByteArray()
    }
}

internal class KnowledgeFixtureDocuments : DocumentStorage {
    private val files = linkedMapOf<String, DocumentStorage.File>()

    override fun references(directory: String) = files.keys.filter { it.startsWith("$directory/") }

    override fun list(directory: String) = references(directory).map { files.getValue(it) }

    override fun read(
        reference: String,
        maxBytes: Int,
    ) = files.getValue(reference).also {
        require(it.bytes.size <= maxBytes)
    }

    override fun save(
        directory: String,
        name: String,
        bytes: ByteArray,
        previous: DocumentStorage.File?,
        mimeType: String,
    ): DocumentStorage.File {
        val reference = "$directory/$name"
        check(files[reference]?.hash == previous?.hash)
        return DocumentStorage.File(reference, name, bytes.copyOf(), DocumentStorage.hash(bytes)).also {
            files[reference] = it
        }
    }

    override fun delete(previous: DocumentStorage.File) {
        check(files[previous.reference]?.hash == previous.hash)
        files.remove(previous.reference)
    }
}

private class NoPdfNetwork : HttpFetchBridge {
    override fun fetch(request: HttpFetchRequest): HttpFetchOutcome {
        error("No network permitted")
    }
}
