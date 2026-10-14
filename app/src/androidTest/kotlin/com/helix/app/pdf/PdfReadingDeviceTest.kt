package com.helix.app.pdf

import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.files.PdfReadException
import com.helix.tools.files.PdfSourceSnapshot
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Generated fixtures only. Compiling this class does not mean native/device acceptance passed. */
class PdfReadingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun call() =
        ExecutableToolCall(
            "pdf-device",
            "files.read_pdf",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
        )

    @Test fun packagedParserIsPrivateIsolatedAndSeparateFromMain() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, PdfReaderService::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertTrue(info.processName.endsWith(":pdf_reader"))
    }

    @Test fun realNativeReaderExtractsOrRendersTheRequestedPageWithoutChangingSource() {
        withReader { root, reader ->
            val pdf = File(root, "fixture.pdf")
            val document = PdfDocument()
            try {
                for (number in 1..2) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(320, 420, number).create())
                    page.canvas.drawText("PDF_PAGE_${number}_EXPECTED", 20f, 60f, Paint().apply { textSize = 16f })
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val before = pdf.readBytes()
            val textOrImage = reader.read(call(), "scope:pdf:fixture.pdf", 2, false)
            assertEquals(2, textOrImage.pageCount)
            assertEquals(64, textOrImage.sourceSha256.length)
            if (Build.VERSION.SDK_INT >= 35) {
                assertTrue(textOrImage.textExtractionSupported)
                assertTrue(textOrImage.text.contains("PDF_PAGE_2_EXPECTED"))
            } else {
                assertFalse(textOrImage.textExtractionSupported)
                assertNotNull(textOrImage.png)
            }
            val rendered = reader.read(call(), "scope:pdf:fixture.pdf", 1, true)
            assertTrue(requireNotNull(rendered.png).size in 1..PdfSourceSnapshot.MAX_PNG_BYTES)
            assertArrayEquals(before, pdf.readBytes())
            assertEquals(
                "PAGE_OUT_OF_RANGE",
                assertThrows(PdfReadException::class.java) {
                    reader.read(call(), "scope:pdf:fixture.pdf", 3, false)
                }.code,
            )
        }
    }

    @Test fun invalidInputAndExpiredCallsAreExplicitFailures() {
        withReader { root, reader ->
            File(root, "invalid.pdf").writeText("%PDF-1.7\nbroken fixture")
            val failure =
                assertThrows(PdfReadException::class.java) {
                    reader.read(call(), "scope:pdf:invalid.pdf", 1, false)
                }
            assertTrue(failure.code in setOf("PDF_INVALID", "PDF_IO_OR_IMAGE_LIMIT"))
            assertEquals(
                "TIMED_OUT",
                assertThrows(PdfReadException::class.java) {
                    reader.read(call().copy(deadline = Instant.EPOCH), "scope:pdf:invalid.pdf", 1, false)
                }.code,
            )
        }
    }

    private fun withReader(block: (File, AndroidPdfPageReader) -> Unit) {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-device-").toFile()
        try {
            val store =
                WorkspaceArtifactStore(
                    ScopeRootResolver { id ->
                        require(id == "pdf") { "Unknown test scope" }
                        root.toPath()
                    },
                )
            block(root, AndroidPdfPageReader(context, store))
        } finally {
            root.deleteRecursively()
        }
    }
}
