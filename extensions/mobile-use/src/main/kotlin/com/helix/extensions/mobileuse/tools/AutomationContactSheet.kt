package com.helix.extensions.mobileuse.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.helix.core.model.VisionLimits
import com.helix.extensions.mobileuse.automation.AutomationScreenshot
import java.io.ByteArrayOutputStream
import java.io.IOException

/** At most four 1280px tiles, decoded one at a time; the result is observation-only. */
internal class AutomationContactSheet : ScreenshotSheet {
    private var bitmap: Bitmap? = null
    private var tileWidth = 0
    private var tileHeight = 0

    override fun add(
        capture: AutomationScreenshot,
        index: Int,
        count: Int,
    ) {
        val png = requireNotNull(capture.png)
        require(png.size <= VisionLimits.MAX_INPUT_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        require(bounds.outWidth == capture.width && bounds.outHeight == capture.height)
        require(capture.width > 0 && capture.height > 0)
        require(capture.width.toLong() * capture.height <= VisionLimits.MAX_TOTAL_PIXELS)
        if (bitmap == null) {
            val scale = minOf(1.0, 1280.0 / maxOf(capture.width, capture.height))
            tileWidth = (capture.width * scale).toInt().coerceAtLeast(1)
            tileHeight = (capture.height * scale).toInt().coerceAtLeast(1)
            bitmap =
                Bitmap.createBitmap(
                    tileWidth * 2,
                    (tileHeight + LABEL_HEIGHT) * ((count + 1) / 2),
                    Bitmap.Config.ARGB_8888,
                )
            bitmap?.eraseColor(Color.BLACK)
        }
        val source = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
        try {
            val canvas = Canvas(checkNotNull(bitmap))
            val left = index % 2 * tileWidth
            val top = index / 2 * (tileHeight + LABEL_HEIGHT)
            canvas.drawBitmap(
                source,
                null,
                Rect(
                    left,
                    top + LABEL_HEIGHT,
                    left + tileWidth,
                    top + LABEL_HEIGHT + tileHeight,
                ),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            canvas.drawText(
                "Frame $index",
                left + 8f,
                top + 24f,
                Paint().apply {
                    color = Color.WHITE
                    textSize = 22f
                },
            )
        } finally {
            source.recycle()
        }
    }

    override fun finish(scope: String?): AutomationScreenshot {
        val image = checkNotNull(bitmap)
        val bytes =
            object : ByteArrayOutputStream() {
                override fun write(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    if (count.toLong() + length >
                        VisionLimits.MAX_INPUT_BYTES
                    ) {
                        throw IOException("Contact sheet budget exceeded")
                    }
                    super.write(buffer, offset, length)
                }

                override fun write(value: Int) {
                    if (count >= VisionLimits.MAX_INPUT_BYTES) throw IOException("Contact sheet budget exceeded")
                    super.write(value)
                }
            }
        check(image.compress(Bitmap.CompressFormat.PNG, 100, bytes))
        return AutomationScreenshot(
            "SAVED",
            bytes.toByteArray(),
            image.width,
            image.height,
            acquisitionScopeRef = scope,
        )
    }

    override fun close() {
        bitmap?.recycle()
        bitmap = null
    }

    private companion object {
        const val LABEL_HEIGHT = 32
    }
}
