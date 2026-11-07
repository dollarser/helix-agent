package com.helix.feature.files.test

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.VisionLimits
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.NormalizedImageExif
import com.helix.feature.files.ImageNormalizer
import com.helix.feature.files.NormalizationCode
import com.helix.feature.files.NormalizationOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            assertTrue(NormalizedImageExif.isExifFree(Files.readAllBytes(image.file), image.mediaType))
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
            assertTrue(NormalizedImageExif.isExifFree(Files.readAllBytes(image.file), image.mediaType))
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
            assertTrue(NormalizedImageExif.isExifFree(Files.readAllBytes(image.file), image.mediaType))
        }
    }

    @Test
    fun rawNamedNormalizedJpegIsNeverOverwrittenWhenTargetIsItsParent() {
        withImage(Bitmap.CompressFormat.JPEG, "image/jpeg") { source, target ->
            val raw = Files.move(source, target.resolve("normalized.jpg"))
            addPrivateExif(raw)
            val original = Files.readAllBytes(raw)
            val originalHash = AtomicFileWriter.sha256Hex(raw)

            val result = ImageNormalizer.normalize(raw, "image/jpeg", target)
            assertTrue("same-directory normalization must succeed: $result", result is NormalizationOutcome.Ok)
            val image = (result as NormalizationOutcome.Ok).image
            assertNotEquals(raw.toRealPath(), image.file.toRealPath())
            assertArrayEquals(original, Files.readAllBytes(raw))
            assertEquals(originalHash, AtomicFileWriter.sha256Hex(raw))
            assertEquals(image.sha256, AtomicFileWriter.sha256Hex(image.file))
            assertEquals(16, image.width)
            assertEquals(32, image.height)
            assertPrivateExifRemoved(image.file)
        }
    }

    @Test
    fun concurrentNormalizationsInOneDestinationKeepIndependentVerifiedFiles() {
        var workersStopped = true
        withImage(Bitmap.CompressFormat.JPEG, "image/jpeg", canCleanup = { workersStopped }) { source, target ->
            val raw = Files.move(source, target.resolve("normalized.jpg"))
            addPrivateExif(raw)
            val original = Files.readAllBytes(raw)
            val secondSource = target.resolve("other.jpg")
            writeDistinctJpeg(secondSource)
            val secondOriginal = Files.readAllBytes(secondSource)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            workersStopped = false
            val outcomes =
                try {
                    val jobs =
                        listOf(raw, secondSource).map { input ->
                            executor.submit<NormalizationOutcome> {
                                ready.countDown()
                                check(start.await(10, TimeUnit.SECONDS))
                                ImageNormalizer.normalize(input, "image/jpeg", target)
                            }
                        }
                    assertTrue("both normalizers must be ready", ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    jobs.map { it.get(20, TimeUnit.SECONDS) }
                } finally {
                    start.countDown()
                    executor.shutdownNow()
                    workersStopped = executor.awaitTermination(10, TimeUnit.SECONDS)
                    assertTrue("normalizers must stop before cleanup", workersStopped)
                }

            val images =
                outcomes.map { result ->
                    assertTrue("concurrent normalization must succeed: $result", result is NormalizationOutcome.Ok)
                    (result as NormalizationOutcome.Ok).image
                }
            assertEquals(2, images.map { it.file.toRealPath() }.toSet().size)
            assertEquals(2, images.map { it.sha256 }.toSet().size)
            images.forEach { image ->
                assertNotEquals(raw.toRealPath(), image.file.toRealPath())
                assertNotEquals(secondSource.toRealPath(), image.file.toRealPath())
                assertEquals(image.sizeBytes, Files.size(image.file))
                assertEquals(image.sha256, AtomicFileWriter.sha256Hex(image.file))
                assertPrivateExifRemoved(image.file)
            }
            assertEquals(16 to 32, images[0].width to images[0].height)
            assertEquals(24 to 12, images[1].width to images[1].height)
            assertArrayEquals(original, Files.readAllBytes(raw))
            assertArrayEquals(secondOriginal, Files.readAllBytes(secondSource))
        }
    }

    @Test
    fun decodeFailureLeavesRawAndAlreadyPublishedImageUnchanged() {
        withImage(Bitmap.CompressFormat.JPEG, "image/jpeg") { source, target ->
            val first = ImageNormalizer.normalize(source, "image/jpeg", target)
            assertTrue(first is NormalizationOutcome.Ok)
            val published = (first as NormalizationOutcome.Ok).image
            val publishedBytes = Files.readAllBytes(published.file)
            val corrupt = target.resolve("normalized.jpg")
            val corruptBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0)
            Files.write(corrupt, corruptBytes)

            val failure = ImageNormalizer.normalize(corrupt, "image/jpeg", target)
            assertTrue(failure is NormalizationOutcome.Failed)
            assertEquals(NormalizationCode.DECODE_FAILED, (failure as NormalizationOutcome.Failed).code)
            assertArrayEquals(corruptBytes, Files.readAllBytes(corrupt))
            assertArrayEquals(publishedBytes, Files.readAllBytes(published.file))
            assertEquals(published.sha256, AtomicFileWriter.sha256Hex(published.file))
        }
    }

    private fun addPrivateExif(source: Path) {
        ExifInterface(source.toFile()).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_USER_COMMENT, "private-fixture-comment")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,12/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,30/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            saveAttributes()
        }
    }

    private fun assertPrivateExifRemoved(file: Path) {
        val exif = ExifInterface(file.toFile())
        listOf(
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
        ).forEach { tag -> assertNull("normalized image must not contain $tag", exif.getAttribute(tag)) }
    }

    private fun withImage(
        format: Bitmap.CompressFormat,
        mime: String,
        canCleanup: () -> Boolean = { true },
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
            // A timed-out worker may still use these private files; retain them rather than race cleanup.
            if (canCleanup()) root.toFile().deleteRecursively()
        }
    }
}

private fun writeDistinctJpeg(path: Path) {
    val bitmap = Bitmap.createBitmap(24, 12, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(0xff4488cc.toInt())
        Files.newOutputStream(path).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
        }
    } finally {
        bitmap.recycle()
    }
}
