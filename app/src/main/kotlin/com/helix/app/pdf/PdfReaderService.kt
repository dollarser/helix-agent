package com.helix.app.pdf

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
import com.helix.tools.files.PdfSourceSnapshot
import java.io.FilterOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** No AppContainer, workspace paths, credentials or network; one native request per isolated instance. */
class PdfReaderService : Service() {
    private val used = AtomicBoolean(false)

    private fun checkHost() = check(Binder.getCallingUid() == applicationInfo.uid)

    private val binder =
        object : IPdfPageReader.Stub() {
            override fun read(
                source: ParcelFileDescriptor,
                image: ParcelFileDescriptor,
                page: Int,
                forceImage: Boolean,
            ): Bundle {
                checkHost()
                check(used.compareAndSet(false, true)) { "PDF instance already used" }
                return source.use { parse(it, image, page, forceImage) }
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
        page: Int,
        forceImage: Boolean,
    ): Bundle =
        image.use {
            try {
                PdfRenderer(source).use { renderer -> readPage(renderer, image, page, forceImage) }
            } catch (_: SecurityException) {
                error("PDF_ENCRYPTED_OR_RESTRICTED")
            } catch (_: IllegalArgumentException) {
                error("PDF_INVALID")
            } catch (_: IOException) {
                error("PDF_IO_OR_IMAGE_LIMIT")
            }
        }

    private fun readPage(
        renderer: PdfRenderer,
        image: ParcelFileDescriptor,
        number: Int,
        forceImage: Boolean,
    ): Bundle {
        if (number !in 1..renderer.pageCount) return error("PAGE_OUT_OF_RANGE")
        return renderer.openPage(number - 1).use { page ->
            val extracted = extractText(page)
            val render = forceImage || extracted.first.isBlank()
            if (render) renderPage(page, image)
            Bundle().apply {
                putInt("pageCount", renderer.pageCount)
                putString("text", extracted.first)
                putBoolean("textTruncated", extracted.second)
                putBoolean("textExtractionSupported", Build.VERSION.SDK_INT >= 35)
                putBoolean("image", render)
            }
        }
    }

    private fun extractText(page: PdfRenderer.Page): Pair<String, Boolean> {
        val text = StringBuilder()
        var truncated = false
        if (Build.VERSION.SDK_INT >= 35) {
            for (content in page.textContents) {
                val remaining = PdfSourceSnapshot.MAX_TEXT - text.length
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                val value = content.text
                text.append(value.take(remaining))
                if (value.length > remaining) truncated = true
                if (text.length < PdfSourceSnapshot.MAX_TEXT) {
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
    ) {
        require(page.width in 1..100_000 && page.height in 1..100_000)
        val scale =
            minOf(
                1.0,
                1600.0 / maxOf(page.width, page.height),
                sqrt(524_288.0 / (page.width.toDouble() * page.height)),
            )
        val bitmap =
            androidx.core.graphics.createBitmap(
                maxOf(1, (page.width * scale).toInt()),
                maxOf(1, (page.height * scale).toInt()),
                Bitmap.Config.ARGB_8888,
            )
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
                            if (count > PdfSourceSnapshot.MAX_PNG_BYTES) throw IOException("PNG limit")
                        }
                    }
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)) { "PDF image encoding failed" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun error(code: String) = Bundle().apply { putString("error", code) }
}
