package com.helix.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * HXA-055 Phase 1 device evidence: measures the on-device image-decode/memory envelope that
 * the centralized vision limits must fit under, and pins the fail-closed invariants the
 * production normalizer relies on.
 *
 * Evidence channel: every measurement is logged with the [TAG] prefix (collected via
 * `adb logcat -s HELIX_VISION_PROBE`); the tests themselves assert only the invariants that
 * hold on every device (a bomb header must not kill the process, the re-encode must strip
 * EXIF, the header dimensions are forgeable and therefore untrusted for memory math).
 *
 * Environment scope (same criterion as HXA-050~054): API 29 / API 36 arm64 emulators,
 * 4 KiB page; no API 34 or x86_64 image, no physical device in this environment.
 */
@Suppress(
    "SwallowedException",
    "LoopWithTooManyJumpStatements",
) // device MEASUREMENT suite: caught OOMs map to closed labels; segment walks are bounded scans
class ImageNormalizationDeviceProbe {
    @Test
    fun decodeLadderReportsTheDeviceMemoryBoundary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = Runtime.getRuntime()
        Log.i(TAG, "HEAP max=${runtime.maxMemory() / MIB}MiB free=${runtime.freeMemory() / MIB}MiB")
        // Bitmap pixels live in NATIVE memory (not the JVM heap) — the real decode budget.
        val mem =
            context
                .getSystemService(android.app.ActivityManager::class.java)
                .let { am ->
                    android.app.ActivityManager
                        .MemoryInfo()
                        .also { am.getMemoryInfo(it) }
                }
        Log.i(
            TAG,
            "NATIVE totalMem=${mem.totalMem / MIB}MiB availMem=${mem.availMem / MIB}MiB " +
                "lowMemoryThreshold=${mem.threshold / MIB}MiB",
        )

        var lastSuccessMp = 0L
        for (megapixels in LADDER_MP) {
            val fixture =
                try {
                    makeSolidJpeg(context, megapixels)
                } catch (e: OutOfMemoryError) {
                    Log.i(TAG, "LADDER mp=$megapixels FIXTURE_OOM (cannot even create the source bitmap)")
                    break
                }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(fixture.absolutePath, bounds)
            val decoded =
                try {
                    BitmapFactory.decodeFile(fixture.absolutePath)
                } catch (e: OutOfMemoryError) {
                    Log.i(TAG, "LADDER mp=$megapixels DECODE_OOM header=${bounds.outWidth}x${bounds.outHeight}")
                    null
                }
            if (decoded == null) {
                Log.i(TAG, "LADDER mp=$megapixels DECODE_FAIL header=${bounds.outWidth}x${bounds.outHeight}")
                break // larger sizes will not decode either — stop, do not risk repeated OOM
            }
            lastSuccessMp = megapixels
            Log.i(
                TAG,
                "LADDER mp=$megapixels OK dims=${decoded.width}x${decoded.height} " +
                    "bytes=${decoded.allocationByteCount / MIB}MiB header=${bounds.outWidth}x${bounds.outHeight}",
            )
            decoded.recycle()
            fixture.delete()
        }
        Log.i(TAG, "LADDER_RESULT maxDecodedMp=$lastSuccessMp maxHeapMiB=${runtime.maxMemory() / MIB}")
    }

    @Test
    fun forgedSofHeaderFailsClosedWithoutKillingTheProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A real 2x2 JPEG whose SOF dimensions are patched to claim 30000x30000 (9e8 px):
        // a ~1 KiB file that lies about its size. The decoder must fail (OOM or error) and
        // the process must survive — the normalizer catches this as a safe decode failure.
        val bomb = forgeBombJpeg(context)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(bomb.absolutePath, bounds)
        Log.i(TAG, "BOMB headerClaims=${bounds.outWidth}x${bounds.outHeight} fileBytes=${bomb.length()}")
        // Measured on API 29/36: the bounds probe reports the forged 30000x30000 as-is
        // (it trusts the SOF header); 65535x65535 is rejected outright (-1x-1). Either way
        // the header is UNTRUSTED for memory math — the input byte budget gates first.
        val forgedVisible = bounds.outWidth == 30_000 && bounds.outHeight == 30_000
        if (forgedVisible) {
            Log.i(TAG, "BOMB headerForgeryVisible=true (bounds probe trusts the SOF header)")
        } else {
            Log.i(TAG, "BOMB headerRejectedAtBounds=true (platform rejected the forged header)")
        }
        val decoded =
            try {
                BitmapFactory.decodeFile(bomb.absolutePath)
            } catch (e: OutOfMemoryError) {
                Log.i(TAG, "BOMB decode=OOM_CAUGHT (process alive)")
                null
            } catch (e: Exception) {
                Log.i(TAG, "BOMB decode=ERROR ${e::class.simpleName} (process alive)")
                null
            }
        // DESIGN INVARIANT: a ~1 KiB file claiming an enormous SOF must never produce an
        // enormous bitmap — either the header is rejected at the bounds stage, the decode
        // fails (OOM/error/null), or the decoder ignores the header and yields the true 2x2.
        if (decoded == null) {
            Log.i(TAG, "BOMB decode=NULL_SAFE (no bitmap produced, process alive)")
        }
        if (decoded != null) {
            val tiny = decoded.width * decoded.height <= 100_000
            Log.i(
                TAG,
                "BOMB decode=DECODED ${decoded.width}x${decoded.height} headerIgnored=$tiny (process alive)",
            )
            decoded.recycle()
            assertTrue("a bomb must never yield a large bitmap", tiny)
        }
        bomb.delete()
    }

    @Test
    fun exifOrientationAndMetadataStripBehavior() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 1000x500 with EXIF Orientation=90CW + GPS latitude + a comment marker.
        val src = makeExifJpeg(context)
        val written = ExifInterface(src)
        Log.i(
            TAG,
            "EXIF written orientation=${written.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)} " +
                "gpsLat=${written.getAttribute(ExifInterface.TAG_GPS_LATITUDE)}",
        )
        val bitmap = BitmapFactory.decodeFile(src.absolutePath)
        assertTrue("exif fixture must decode", bitmap != null)
        Log.i(
            TAG,
            "EXIF bitmapDimsAfterDecode=${bitmap!!.width}x${bitmap.height} " +
                "(autoRotated=${bitmap.width < bitmap.height})",
        )
        // Re-encode path: Bitmap.compress writes NO EXIF — verify the metadata is gone (strip).
        val out = File(context.cacheDir, "exif-stripped.jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val stripped = ExifInterface(out)
        val orientationAfter = stripped.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        val gpsAfter = stripped.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
        val commentAfter = stripped.getAttribute(ExifInterface.TAG_USER_COMMENT)
        Log.i(
            TAG,
            "EXIF strippedOrientation=$orientationAfter strippedGps=${gpsAfter == null} " +
                "strippedComment=${commentAfter == null}",
        )
        // Invariant: the re-encode must not carry the source EXIF (privacy strip works).
        assertTrue("re-encoded jpeg must carry no GPS", gpsAfter == null)
        assertTrue("re-encoded jpeg must carry no comment", commentAfter == null)
        assertTrue("re-encoded jpeg must carry no orientation", orientationAfter == 0)
        src.delete()
        out.delete()
    }

    @Test
    fun base64BudgetArithmeticForNormalizedOutputs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (side in intArrayOf(1024, 2048, 2576, 4096)) {
            val bmp = Bitmap.createBitmap(side, (side * 3) / 4, Bitmap.Config.ARGB_8888)
            val out = File(context.cacheDir, "budget-$side.jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            val raw = out.length()
            val b64 = Base64.encodeToString(out.readBytes(), Base64.NO_WRAP).length
            Log.i(
                TAG,
                "BUDGET side=$side rawKiB=${raw / 1024} b64KiB=${b64 / 1024} " +
                    "headroomUnder4MiBb64Pct=${(4 * MIB - b64).coerceAtLeast(0) * 100 / (4 * MIB)}",
            )
            out.delete()
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** A solid-color JPEG with roughly [megapixels] megapixels (width = 1024*mp, height = 1024). */
    private fun makeSolidJpeg(
        context: Context,
        megapixels: Long,
    ): File {
        val width = (1024L * megapixels).toInt()
        val bmp = Bitmap.createBitmap(width, 1024, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { color = 0xFF804020.toInt() }
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
        val file = File(context.cacheDir, "ladder-$megapixels.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return file
    }

    /**
     * Patches the SOF width/height of a tiny real JPEG to claim 30000x30000 (9e8 px,
     * ~3.6 GiB of ARGB_8888) while the actual pixel data is a 2x2 square — a ~1 KiB file
     * that lies about its size.
     */
    private fun forgeBombJpeg(context: Context): File {
        val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "bomb.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        val bytes = file.readBytes()
        // Walk JPEG segments from the SOI: each segment is marker(FF xx) + big-endian
        // length (counting the two length bytes themselves). The SOF family is C0..CF
        // except C4 (DHT) and C8 (JPG) — Android's encoder emits progressive (C2), so the
        // baseline-only C0 search is not enough.
        var i = 2 // skip SOI (FF D8)
        var sof = -1
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) break
            val marker = bytes[i + 1].toInt() and 0xFF
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8) {
                sof = i
                break
            }
            i += 2 + length
        }
        require(sof > 0) { "fixture jpeg must contain a SOF segment (baseline or progressive)" }
        Log.i(
            TAG,
            "BOMB sofAt=$sof marker=${"0x%02X".format(bytes[sof + 1].toInt() and 0xFF)} " +
                "before=${bytes.slice(sof until sof + 12).joinToString(" ") { "%02X".format(it) }}",
        )
        // SOF layout after the length: precision(1) height(2) width(2), all big-endian.
        // 30000 = 0x7530 per dimension (9e8 px, ~3.6 GiB of ARGB_8888) — below the value
        // the platform rejects outright at the bounds stage (65535 measured to be rejected
        // on API 29/36), so the forged dimensions stay visible to inJustDecodeBounds.
        bytes[sof + 5] = 0x75
        bytes[sof + 6] = 0x30.toByte()
        bytes[sof + 7] = 0x75
        bytes[sof + 8] = 0x30.toByte()
        Log.i(
            TAG,
            "BOMB after=${bytes.slice(sof until sof + 12).joinToString(" ") { "%02X".format(it) }}",
        )
        file.writeBytes(bytes)
        return file
    }

    private fun makeExifJpeg(context: Context): File {
        val bmp = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "exif-src.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        ExifInterface(file).apply {
            // SDK 36 stubs expose only setAttribute (typed setters removed); orientation 6 = 90CW.
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,12/1,0/1")
            setAttribute(ExifInterface.TAG_USER_COMMENT, "helix-vision-probe-marker")
            saveAttributes()
        }
        return file
    }

    private companion object {
        const val TAG = "HELIX_VISION_PROBE"
        const val MIB = 1024L * 1024L
        val LADDER_MP = longArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)
    }
}
