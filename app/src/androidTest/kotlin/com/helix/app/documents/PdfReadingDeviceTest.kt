package com.helix.app.documents

import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.extensions.documents.DocumentException
import com.helix.extensions.documents.DocumentLimits
import com.helix.extensions.documents.DocumentSnapshot
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
            "documents.read",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            NoCancellation,
        )

    @Test fun packagedParserIsPrivateIsolatedAndSeparateFromMain() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getServiceInfo(ComponentName(context, DocumentReaderService::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
        assertTrue(info.processName.endsWith(":document_reader"))
    }

    @Test fun realNativeReaderExtractsOrRendersTheRequestedPageWithoutChangingSource() {
        withReader { root, reader ->
            val pdf = File(root, "fixture.pdf")
            val document = PdfDocument()
            try {
                for (number in 1..2) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(320, 420, number).create())
                    page.canvas.drawText("PDF_PAGE_${number}_EXPECTED", 20f, 60f, Paint().apply { textSize = 16f })
                    page.canvas.drawRect(0f, 210f, 320f, 420f, Paint().apply { color = android.graphics.Color.RED })
                    document.finishPage(page)
                }
                pdf.outputStream().use(document::writeTo)
            } finally {
                document.close()
            }
            val before = pdf.readBytes()
            val textOrImage = parse(reader, "fixture.pdf", 2, "read")
            assertEquals(2, textOrImage.metadata["pageCount"]!!.jsonPrimitive.int)
            assertEquals("page:2", textOrImage.metadata["coverage"]!!.jsonPrimitive.content)
            if (Build.VERSION.SDK_INT >= 35) {
                assertTrue(textOrImage.metadata["textExtractionSupported"]!!.jsonPrimitive.boolean)
                assertTrue(
                    textOrImage.metadata["text"]!!
                        .jsonPrimitive.content
                        .contains("PDF_PAGE_2_EXPECTED"),
                )
            } else {
                assertFalse(textOrImage.metadata["textExtractionSupported"]!!.jsonPrimitive.boolean)
                assertTrue(
                    textOrImage.metadata["text"]!!
                        .jsonPrimitive.content
                        .isEmpty(),
                )
                assertNotNull(parse(reader, "fixture.pdf", 2, "view").image)
            }
            val rendered = parse(reader, "fixture.pdf", 1, "view")
            assertTrue(requireNotNull(rendered.image).size in 1..DocumentLimits.IMAGE_BYTES)
            val cropped = parse(reader, "fixture.pdf", 1, "view", region = floatArrayOf(0f, 0.5f, 1f, 1f))
            val cropBytes = requireNotNull(cropped.image)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(cropBytes, 0, cropBytes.size)
            try {
                assertEquals(android.graphics.Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally {
                bitmap.recycle()
            }
            assertArrayEquals(before, pdf.readBytes())
            assertEquals(
                "RANGE_OUT_OF_BOUNDS",
                assertThrows(DocumentException::class.java) {
                    parse(reader, "fixture.pdf", 3, "read")
                }.code,
            )
        }
    }

    @Test fun invalidInputAndExpiredCallsAreExplicitFailures() {
        withReader { root, reader ->
            File(root, "invalid.pdf").writeText("%PDF-1.7\nbroken fixture")
            val failure =
                assertThrows(DocumentException::class.java) {
                    parse(reader, "invalid.pdf", 1, "read")
                }
            assertTrue(failure.code in setOf("MALFORMED_DOCUMENT", "MALFORMED_DOCUMENT_OR_RESOURCE_LIMIT"))
            assertEquals(
                "TIMED_OUT",
                assertThrows(DocumentException::class.java) {
                    parse(reader, "invalid.pdf", 1, "read", expired = true)
                }.code,
            )
        }
    }

    @Test fun realIsolatedDocxWorkerReadsChineseTableAndEmbeddedPixels() {
        withReader { root, reader ->
            writeDocxFixture(root)
            val snapshot = DocumentSnapshot("fixture", "scope:pdf:fixture.docx", "a".repeat(64), "docx")
            val text = reader.parse(call(), snapshot, "read").metadata
            assertEquals(3, text.getValue("blocks").jsonArray.size)
            assertTrue(text.toString().contains("中文文档测试"))
            assertTrue(text.toString().contains("42"))
            val viewed = reader.parse(call().copy(args = buildJsonObject { put("imageId", "pic") }), snapshot, "view")
            val bytes = requireNotNull(viewed.image)
            val image = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(android.graphics.Color.GREEN, image.getPixel(12, 12))
            } finally {
                image.recycle()
            }
        }
    }

    private fun writeDocxFixture(root: File) {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val pack = "http://schemas.openxmlformats.org/package/2006"
        val png = java.io.ByteArrayOutputStream()
        val fixture = androidx.core.graphics.createBitmap(24, 24)
        try {
            fixture.eraseColor(android.graphics.Color.GREEN)
            assertTrue(fixture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png))
        } finally {
            fixture.recycle()
        }
        val parts =
            mapOf(
                "[Content_Types].xml" to (
                    """
                    <Types xmlns="$pack/content-types">
                    <Override PartName="/word/document.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.trimIndent().toByteArray()
                ),
                "_rels/.rels" to (
                    """
                    <Relationships xmlns="$pack/relationships">
                    <Relationship Id="main" Type="$r/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """.trimIndent().toByteArray()
                ),
                "word/document.xml" to (
                    """
                    <w:document xmlns:w="$w" xmlns:r="$r"
                    xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body>
                    <w:p><w:r><w:t>中文文档测试</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>42</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    <w:p><w:r><w:drawing><a:blip r:embed="pic"/></w:drawing></w:r></w:p>
                    </w:body></w:document>
                    """.trimIndent().toByteArray()
                ),
                "word/_rels/document.xml.rels" to (
                    """
                    <Relationships xmlns="$pack/relationships">
                    <Relationship Id="pic" Type="$r/image" Target="media/pic.png"/>
                    </Relationships>
                    """.trimIndent().toByteArray()
                ),
                "word/media/pic.png" to png.toByteArray(),
            )
        java.util.zip.ZipOutputStream(File(root, "fixture.docx").outputStream()).use { zip ->
            parts.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun parse(
        reader: AndroidDocumentParser,
        path: String,
        page: Int,
        operation: String,
        expired: Boolean = false,
        region: FloatArray? = null,
    ): com.helix.extensions.documents.DocumentResult {
        val call =
            call().copy(
                args =
                    buildJsonObject {
                        put("page", page)
                        region?.let { area -> putJsonArray("region") { area.forEach { add(it) } } }
                    },
                deadline = if (expired) Instant.EPOCH else Instant.now().plusSeconds(20),
            )
        return reader.parse(call, DocumentSnapshot("fixture", "scope:pdf:$path", "a".repeat(64), "pdf"), operation)
    }

    private fun withReader(block: (File, AndroidDocumentParser) -> Unit) {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-device-").toFile()
        try {
            block(
                root,
                AndroidDocumentParser(
                    context,
                ) { _, snapshot -> File(root, snapshot.sourcePath.substringAfterLast(':')) },
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
