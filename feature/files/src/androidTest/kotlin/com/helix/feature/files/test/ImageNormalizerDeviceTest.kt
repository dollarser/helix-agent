package com.helix.feature.files.test

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.VisionLimits
import com.helix.feature.files.ImageNormalizer
import com.helix.feature.files.NormalizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ImageNormalizerDeviceTest {
    @Test
    fun oversizedEncodedPngUsesTheDownscaleLadder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "image-budget-")
        try {
            val source = root.resolve("noise.png")
            val random = java.util.Random(42)
            val pixels = IntArray(1024 * 1024) { random.nextInt() or (0xff shl 24) }
            val bitmap = Bitmap.createBitmap(pixels, 1024, 1024, Bitmap.Config.ARGB_8888)
            try {
                Files.newOutputStream(source).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 85, it)) }
            } finally {
                bitmap.recycle()
            }
            assertTrue("fixture must require downscaling", Files.size(source) > VisionLimits.MAX_NORMALIZED_RAW_BYTES)
            val result = ImageNormalizer.normalize(source, "image/png", Files.createDirectory(root.resolve("output")))
            assertTrue("valid oversized encoding should downscale: $result", result is NormalizationOutcome.Ok)
            val image = (result as NormalizationOutcome.Ok).image
            assertTrue(image.width < 1024)
            assertEquals(image.width, image.height)
            assertTrue(image.sizeBytes <= VisionLimits.MAX_NORMALIZED_RAW_BYTES)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun webpNormalizesOnMinSdkAndKeepsItsMimeType() {
        withImage(Bitmap.CompressFormat.WEBP, "image/webp") { source, target ->
            val result = ImageNormalizer.normalize(source, "image/webp", target)
            assertTrue(result is NormalizationOutcome.Ok)
            val image = (result as NormalizationOutcome.Ok).image
            assertEquals("image/webp", image.mediaType)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.file.toString(), bounds)
            assertEquals("image/webp", bounds.outMimeType)
            assertEquals(32, bounds.outWidth)
            assertEquals(16, bounds.outHeight)
        }
    }

    @Test
    fun jpegOrientationIsAppliedAndPrivateExifIsRemoved() {
        withImage(Bitmap.CompressFormat.JPEG, "image/jpeg") { source, target ->
            ExifInterface(source.toFile()).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_USER_COMMENT, "private-fixture-comment")
                saveAttributes()
            }
            val result = ImageNormalizer.normalize(source, "image/jpeg", target)
            assertTrue(result is NormalizationOutcome.Ok)
            val image = (result as NormalizationOutcome.Ok).image
            assertEquals(16, image.width)
            assertEquals(32, image.height)
            assertNull(ExifInterface(image.file.toFile()).getAttribute(ExifInterface.TAG_USER_COMMENT))
        }
    }

    private fun withImage(
        format: Bitmap.CompressFormat,
        mime: String,
        block: (java.nio.file.Path, java.nio.file.Path) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "image-regression-")
        try {
            val source = root.resolve("source.${mime.substringAfter('/')}")
            val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
            try {
                Files.newOutputStream(source).use { assertTrue(bitmap.compress(format, 85, it)) }
            } finally {
                bitmap.recycle()
            }
            block(source, Files.createDirectory(root.resolve("output")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
