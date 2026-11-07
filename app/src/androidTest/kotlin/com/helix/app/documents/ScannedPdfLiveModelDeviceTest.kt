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
import com.helix.app.provider.LoadedImage
import com.helix.app.provider.ProviderFactory
import com.helix.app.provider.VisionImageSource
import com.helix.core.model.ArtifactRef
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.NoCancellation
import com.helix.extensions.documents.DocumentSnapshot
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Opt-in component acceptance: native raster-PDF page -> production protocol -> real model.
 * Agent autonomous view selection and OCR-service installation are deliberately not implied.
 */
class ScannedPdfLiveModelDeviceTest {
    @Test
    @Suppress("LongMethod") // Synthetic source, real provider and cleanup remain one bounded acceptance unit.
    fun rasterOnlyPageIsRenderedAndReadByExistingModel(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val optIn = args.getString("helix.scannedPdfLive")
            org.junit.Assume.assumeTrue("Explicit scanned-PDF model opt-in absent", optIn != null)
            require(optIn == "true")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HelixApplication
            require(app.packageName == "com.helix.agent.developer")
            app.account.restore()
            check(app.account.signedIn.value)
            val root = File(app.cacheDir, "scanned-live-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val file = File(root, "synthetic.pdf")
                rasterPdf(file)
                val reader = AndroidDocumentParser(app) { _, _ -> file }
                val revision =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                val snapshot = DocumentSnapshot("synthetic", "scope:synthetic:synthetic.pdf", revision, "pdf")
                val call =
                    ExecutableToolCall(
                        "synthetic",
                        "documents.view",
                        "1",
                        JsonObject(mapOf("page" to JsonPrimitive(1))),
                        ExecutionTargetType.LOCAL_ANDROID,
                        Instant.now().plusSeconds(30),
                        NoCancellation,
                    )
                val text = reader.parse(call, snapshot, "read")
                assertTrue(
                    "Raster fixture must not contain a native text layer",
                    !text.metadata.toString().contains("742913"),
                )
                val png = requireNotNull(reader.parse(call, snapshot, "view").image)
                val ref = ArtifactRef("synthetic-raster-page")
                val config = app.awaitAppContainer().providerService.storedConfig(BuiltInModel.PROVIDER_ID)
                val source =
                    VisionImageSource { requested, destination ->
                        require(requested.ref == ref && requested.mediaType == "image/png")
                        require(destination == config)
                        LoadedImage("image/png", Base64.getEncoder().encodeToString(png))
                    }
                val provider =
                    ProviderFactory(
                        { error("No static credentials") },
                        ProviderFactory.defaultWire(),
                        { source },
                        builtInWire = BuiltInAccountWire(app.account),
                    ).create(config)
                val answer = StringBuilder()
                withTimeout(60_000) {
                    provider
                        .stream(
                            ModelRequest(
                                config.model,
                                listOf(
                                    ModelMessage(
                                        ModelRole.USER,
                                        "Read the six digit code in this synthetic image. Reply with the digits only.",
                                        listOf(ImageReference(ref, "image/png")),
                                    ),
                                ),
                                maxOutputTokens = 128,
                            ),
                        ).collect { event ->
                            when (event) {
                                is ModelEvent.TextDelta -> answer.append(event.text)
                                is ModelEvent.Error -> error("Synthetic vision request failed: ${event.code}")
                                else -> Unit
                            }
                        }
                }
                assertTrue("Real model must read raster-only digits", answer.contains("742913"))
                println("SYNTHETIC_RASTER_PDF_REAL_MODEL_VERIFIED componentOnly=true")
            } finally {
                root.deleteRecursively()
            }
        }

    private fun rasterPdf(target: File) {
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText(
            "CODE 742913",
            30f,
            210f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 64f
            },
        )
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 400, 1).create())
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(page)
            target.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
            bitmap.recycle()
        }
    }
}
