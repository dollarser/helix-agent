package com.helix.app.plugin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileReturnPixelDeviceTest {
    @Test fun lightTitle() = verifyTitle(Color.WHITE, Color.BLACK)

    @Test fun darkTitle() = verifyTitle(Color.BLACK, Color.WHITE)

    private fun verifyTitle(
        background: Int,
        foreground: Int,
    ) {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        val bounds = Rect(10, 10, 110, 50)
        try {
            bitmap.eraseColor(background)
            assertTrue("Uniform frame must fail", !titlePixelsRendered(bitmap, bounds))
            val paint =
                Paint().apply {
                    color = foreground
                    textSize = 24f
                }
            Canvas(bitmap).drawText("Helix", 12f, 38f, paint)
            assertTrue("Visible title must pass", titlePixelsRendered(bitmap, bounds))
            assertTrue(!titlePixelsRendered(bitmap, Rect(130, 80, 140, 90)))
        } finally {
            bitmap.recycle()
        }
    }
}

internal fun titlePixelsRendered(
    bitmap: android.graphics.Bitmap,
    bounds: android.graphics.Rect,
): Boolean {
    val clipped = android.graphics.Rect(bounds)
    if (!clipped.intersect(0, 0, bitmap.width, bitmap.height)) return false
    var darkest = 255
    var lightest = 0
    for (y in clipped.top until clipped.bottom step 2) {
        for (x in clipped.left until clipped.right step 2) {
            val pixel = bitmap.getPixel(x, y)
            val brightness =
                (
                    android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) +
                        android.graphics.Color.blue(pixel)
                ) / 3
            darkest = minOf(darkest, brightness)
            lightest = maxOf(lightest, brightness)
        }
    }
    return lightest - darkest >= 32
}
