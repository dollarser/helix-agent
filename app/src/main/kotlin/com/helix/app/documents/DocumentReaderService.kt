package com.helix.app.documents

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.helix.extensions.documents.DocumentException
import com.helix.extensions.documents.DocumentLimits
import com.helix.extensions.documents.DocxReader
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
import java.io.FilterOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** No AppContainer, workspace paths, credentials or network; one native request per isolated instance. */
@Suppress("TooManyFunctions") // Service lifecycle plus small, separate PDF/DOCX and image-job handlers.
class DocumentReaderService : Service() {
    private val used = AtomicBoolean(false)

    private fun checkHost() = check(Binder.getCallingUid() == applicationInfo.uid)

    private val binder =
        object : IDocumentReader.Stub() {
            override fun read(
                source: ParcelFileDescriptor,
                image: ParcelFileDescriptor,
                request: Bundle,
            ): Bundle {
                checkHost()
                check(used.compareAndSet(false, true)) { "PDF instance already used" }
                return source.use { parse(it, image, request) }
            }

            override fun shutdown() {
                checkHost()
                Process.killProcess(Process.myPid())
            }
        }

    override fun onBind(intent: Intent): IBinder? {
        if (!Process.isIsolated()) return null
        // Own-instance lifetime backstop if the host dies or its cancellation path disappears.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, 30_000L)
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }

    private fun parse(
        source: ParcelFileDescriptor,
        image: ParcelFileDescriptor,
        request: Bundle,
    ): Bundle =
        image.use {
            try {
                parseFormat(source, image, request)
            } catch (
                failure: DocumentException,
            ) {
                error(failure.code)
            } catch (
                _: SecurityException,
            ) {
                error("ENCRYPTED_DOCUMENT")
            } catch (
                _: IllegalArgumentException,
            ) {
                error("MALFORMED_DOCUMENT")
            } catch (_: IOException) {
                error("MALFORMED_DOCUMENT_OR_RESOURCE_LIMIT")
            }
        }

    private fun parseFormat(
        source: ParcelFileDescriptor,
        image: ParcelFileDescriptor,
        request: Bundle,
    ): Bundle =
        run {
            val operation = request.getString("operation")
            require(operation in setOf("inspect", "read", "view"))
            when (request.getString("format")) {
                "pdf" -> {
                    PdfRenderer(source).use { renderer ->
                        readPage(renderer, image, request, operation!!)
                    }
                }

                "docx" -> {
                    parseDocx(source, image, request, operation!!)
                }

                else -> {
                    error("UNSUPPORTED_FORMAT")
                }
            }
        }

    private fun parseDocx(
        source: ParcelFileDescriptor,
        image: ParcelFileDescriptor,
        request: Bundle,
        operation: String,
    ): Bundle {
        val result =
            ParcelFileDescriptor
                .AutoCloseInputStream(
                    ParcelFileDescriptor.dup(source.fileDescriptor),
                ).use { input ->
                    DocxReader.read(
                        input,
                        operation,
                        request.getInt("startBlock", 1),
                        request.getString("imageId"),
                    )
                }
        result.image?.let { renderEmbedded(it, image) }
        return Bundle().apply {
            putString("metadata", result.metadata.toString())
            putBoolean(
                "image",
                result.image != null,
            )
        }
    }

    private fun readPage(
        renderer: PdfRenderer,
        image: ParcelFileDescriptor,
        request: Bundle,
        operation: String,
    ): Bundle {
        val number = request.getInt("page", 1)
        if (number !in 1..renderer.pageCount) return error("RANGE_OUT_OF_BOUNDS")
        var text = "" to false
        if (operation != "inspect") {
            renderer.openPage(number - 1).use { page ->
                if (operation == "read") text = extractText(page)
                if (operation == "view") renderPage(page, image, request.getFloatArray("region"))
            }
        }
        val metadata = pageMetadata(renderer.pageCount, number, request, operation, text)
        return Bundle().apply {
            putString("metadata", metadata.toString())
            putBoolean("image", operation == "view")
        }
    }

    private fun pageMetadata(
        pageCount: Int,
        number: Int,
        request: Bundle,
        operation: String,
        text: Pair<String, Boolean>,
    ) = buildJsonObject {
        put(
            "status",
            when {
                operation == "inspect" -> "INSPECTED"
                operation == "read" && text.first.isBlank() -> "CONTENT_UNAVAILABLE"
                else -> "CONTENT_READ"
            },
        )
        put("pageCount", pageCount)
        put("textExtractionSupported", Build.VERSION.SDK_INT >= 35)
        put("truncated", text.second)
        put("partial", operation != "inspect")
        put(
            "coverage",
            if (operation ==
                "inspect"
            ) {
                "page count only; no document prose returned"
            } else {
                "page:$number" +
                    if (request.containsKey("region")) " region" else ""
            },
        )
        putJsonArray("warnings") {
            add(
                (
                    "Text can omit diagrams/layout; scanned pages and Android below API35 " +
                        "require view with model vision."
                ),
            )
        }
        if (operation != "inspect") {
            put("page", number)
            put("text", text.first)
            request.getFloatArray("region")?.let { values ->
                putJsonArray("region") { values.forEach { add(it) } }
            }
            if (number < pageCount) put("nextPage", number + 1)
            put("eof", number == pageCount)
        }
    }

    private fun renderEmbedded(
        bytes: ByteArray,
        output: ParcelFileDescriptor,
    ) {
        val options =
            android.graphics.BitmapFactory
                .Options()
                .apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(
            options.outWidth > 0 && options.outHeight > 0 &&
                options.outWidth.toLong() * options.outHeight <= 16_777_216L,
        )
        var sample = 1
        while (options.outWidth.toLong() * options.outHeight / sample / sample > 524_288L ||
            maxOf(options.outWidth, options.outHeight) / sample > 1600
        ) {
            sample *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val bitmap =
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw IOException("Image decode failed")
        try {
            writeBitmap(bitmap, output)
        } finally {
            bitmap.recycle()
        }
    }

    private fun extractText(page: PdfRenderer.Page): Pair<String, Boolean> {
        val text = StringBuilder()
        var truncated = false
        if (Build.VERSION.SDK_INT >= 35) {
            for (content in page.textContents) {
                val remaining = DocumentLimits.TEXT - text.length
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val value = content.text
                text.append(value.take(remaining))
                if (value.length > remaining) truncated = true
                if (text.length < DocumentLimits.TEXT) {
                    text.append('\n')
                }
            }
        }
        // Do not return an unpaired surrogate when the native text crosses the cap.
        if (text.isNotEmpty() && text.last().isHighSurrogate()) {
            text.setLength(text.length - 1)
            truncated = true
        }
        return text.toString() to truncated
    }

    private fun renderPage(
        page: PdfRenderer.Page,
        output: ParcelFileDescriptor,
        region: FloatArray?,
    ) {
        val area = region ?: floatArrayOf(0f, 0f, 1f, 1f)
        require(area.size == 4 && area.all { it.isFinite() && it in 0f..1f } && area[0] < area[2] && area[1] < area[3])
        val width = page.width * (area[2] - area[0]).toDouble()
        val height = page.height * (area[3] - area[1]).toDouble()
        require(width >= 1 && height >= 1)
        require(page.width in 1..100_000 && page.height in 1..100_000)
        val scale =
            minOf(
                1600.0 / maxOf(width, height),
                sqrt(524_288.0 / (width * height)),
            )
        val bitmap =
            androidx.core.graphics.createBitmap(
                maxOf(1, (width * scale).toInt()),
                maxOf(1, (height * scale).toInt()),
                Bitmap.Config.ARGB_8888,
            )
        try {
            bitmap.eraseColor(Color.WHITE)
            val matrix =
                android.graphics.Matrix().apply {
                    setScale(scale.toFloat(), scale.toFloat())
                    postTranslate((-area[0] * page.width * scale).toFloat(), (-area[1] * page.height * scale).toFloat())
                }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            writeBitmap(bitmap, output)
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeBitmap(
        bitmap: Bitmap,
        output: ParcelFileDescriptor,
    ) {
        ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(output.fileDescriptor)).use { raw ->
            val bounded =
                object : FilterOutputStream(raw) {
                    private var count = 0

                    override fun write(value: Int) {
                        limit(1)
                        out.write(value)
                    }

                    override fun write(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        limit(length)
                        out.write(buffer, offset, length)
                    }

                    private fun limit(length: Int) {
                        count += length
                        if (count > DocumentLimits.IMAGE_BYTES) throw IOException("PNG limit")
                    }
                }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)) { "PDF image encoding failed" }
        }
    }

    private fun error(code: String) = Bundle().apply { putString("error", code) }
}
