package com.helix.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.helix.core.model.VisionLimits
import com.helix.core.workspace.AtomicFileWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import kotlin.math.min

/**
 * The closed failure codes of the on-device image normalization pass (HXA-055, ADR-0014 §4:
 * 「任何解码失败、Artifact 变化或超限均失败关闭，不回退为裸 base64 文本」). Each code maps to an
 * actionable, user-visible refusal at send time; the raw artifact stays local (save/preview).
 */
enum class NormalizationCode {
    /** The input file exceeds [VisionLimits.MAX_INPUT_BYTES] (the import cap — defensive). */
    INPUT_TOO_LARGE,

    /** The input cannot be read or its header cannot be probed. */
    UNREADABLE,

    /** The decode produced no bitmap (corrupt, truncated, or a forged-header bomb). */
    DECODE_FAILED,

    /** The decode ran out of native memory (a bomb or an over-envelope image). */
    DECODE_OUT_OF_MEMORY,

    /**
     * The bounds-probe dimensions exceed the envelope even after the largest practical
     * power-of-two sample — the image is structurally too large to normalize.
     */
    DIMENSIONS_EXCEEDED,

    /** The re-encoded output still exceeds [VisionLimits.MAX_NORMALIZED_RAW_BYTES]. */
    BUDGET_EXCEEDED,
}

/**
 * One successfully normalized image (HXA-055): the re-encoded, EXIF-stripped bytes at [file]
 * (app-private, written atomically), their [sha256], the [mediaType] the wire uses, and the
 * final decoded [width]x[height] (the envelope is guaranteed by construction).
 */
data class NormalizedImage(
    val file: Path,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
)

/** The closed outcome of one normalization attempt. */
sealed interface NormalizationOutcome {
    data class Ok(
        val image: NormalizedImage,
    ) : NormalizationOutcome

    /** [code] plus a SHORT, log-safe detail (no paths, no content, no URIs). */
    data class Failed(
        val code: NormalizationCode,
        val detail: String,
    ) : NormalizationOutcome
}

/**
 * Normalizes one staged image attachment on-device (HXA-055, ADR-0014 §4):
 *
 * 1. the RAW byte budget gates BEFORE any decode ([VisionLimits.MAX_INPUT_BYTES]) — the
 *    measured Phase-1 bomb evidence shows the SOF header is forgeable and visible to
 *    `inJustDecodeBounds`, so the file size is the only pre-decode bound that cannot lie;
 * 2. the bounds probe (header only) must fit the envelope — after the smallest practical
 *    power-of-two `inSampleSize` — or the image is [NormalizationCode.DIMENSIONS_EXCEEDED];
 * 3. the full decode (ARGB_8888, sampled) is caught on OOM — a bomb or over-envelope image
 *    fails as [NormalizationCode.DECODE_OUT_OF_MEMORY], never as a process death (measured);
 * 4. the EXIF orientation (0..7) is applied manually — measured on API 29/36:
 *    `BitmapFactory` does NOT auto-apply it;
 * 5. the re-encode STRIPS all EXIF (measured: `Bitmap.compress` writes no metadata — the
 *    privacy strip IS the re-encode) and the size budget (quality ladder, then edge
 *    downscale, bounded) keeps the output under [VisionLimits.MAX_NORMALIZED_RAW_BYTES].
 *
 * No step ever falls back to raw base64 text: every failure is a closed
 * [NormalizationCode] the send path surfaces as an actionable error.
 */
object ImageNormalizer {
    /** The JPEG/WebP re-encode quality ladder (first fit wins; deterministic, no randomness). */
    private val QUALITY_LADDER = intArrayOf(85, 70, 55)

    /** Below this long edge a further downscale would destroy the image — fail the budget instead. */
    private const val MIN_LONG_EDGE_PX = 512

    @Suppress("ReturnCount", "SwallowedException") // one fail-closed return per closed NormalizationCode step
    fun normalize(
        rawFile: Path,
        rawMediaType: String,
        targetDir: Path,
    ): NormalizationOutcome {
        val file = rawFile.toFile()
        if (!file.exists() || !file.isFile()) {
            return NormalizationOutcome.Failed(NormalizationCode.UNREADABLE, "missing")
        }
        if (file.length() > VisionLimits.MAX_INPUT_BYTES) {
            return NormalizationOutcome.Failed(
                NormalizationCode.INPUT_TOO_LARGE,
                "input exceeds ${VisionLimits.MAX_INPUT_BYTES} bytes",
            )
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return NormalizationOutcome.Failed(NormalizationCode.DECODE_FAILED, "header probe failed")
        }
        val sample = VisionLimits.requiredInSampleSize(bounds.outWidth, bounds.outHeight)
        if (sample == Int.MAX_VALUE) {
            return NormalizationOutcome.Failed(
                NormalizationCode.DIMENSIONS_EXCEEDED,
                "${bounds.outWidth}x${bounds.outHeight} exceeds the ${VisionLimits.MAX_EDGE_PX}px edge / " +
                    "${VisionLimits.MAX_TOTAL_PIXELS} pixel envelope",
            )
        }
        val bitmap =
            try {
                BitmapFactory
                    .Options()
                    .apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }.let { BitmapFactory.decodeFile(file.absolutePath, it) }
            } catch (e: OutOfMemoryError) {
                return NormalizationOutcome.Failed(NormalizationCode.DECODE_OUT_OF_MEMORY, "decode out of memory")
            }
        if (bitmap == null) {
            return NormalizationOutcome.Failed(NormalizationCode.DECODE_FAILED, "decode produced no bitmap")
        }
        val oriented = applyOrientation(bitmap, file)
        val final =
            try {
                encodeWithinBudget(oriented, rawMediaType, targetDir.toFile())
            } finally {
                if (oriented !== bitmap) oriented.recycle()
                if (oriented !== bitmap) bitmap.recycle() else bitmap.recycle()
            }
        return final
    }

    /** Applies the EXIF orientation (measured: the platform decoder does NOT). */
    @Suppress(
        "ReturnCount",
        "SwallowedException",
        "TooGenericExceptionCaught",
    ) // unreadable EXIF keeps the stored orientation (closed)
    private fun applyOrientation(
        bitmap: Bitmap,
        source: File,
    ): Bitmap {
        val orientation =
            try {
                ExifInterface(source)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                return bitmap // unreadable EXIF: keep the stored orientation, re-encode strips the rest
            }
        val matrix = orientationMatrix(orientation) ?: return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    @Suppress("CyclomaticComplexMethod") // the closed 7-value EXIF orientation → matrix map
    private fun orientationMatrix(orientation: Int): Matrix? =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                Matrix().apply { postRotate(90f) }
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                Matrix().apply { postRotate(180f) }
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                Matrix().apply { postRotate(270f) }
            }

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                Matrix().apply { postScale(-1f, 1f) }
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                Matrix().apply { postScale(1f, -1f) }
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                Matrix().apply {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                Matrix().apply {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
            }

            else -> {
                null
            }
        }

    /**
     * Re-encodes [bitmap] (EXIF-stripped by construction — `compress` writes no metadata) into
     * [targetDir]/normalized.<ext>, walking the quality ladder then a bounded 0.75x edge
     * downscale until the output fits [VisionLimits.MAX_NORMALIZED_RAW_BYTES].
     */
    @Suppress(
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "SwallowedException",
        "TooGenericExceptionCaught",
    ) // scale × quality ladder; a platform compress failure is a closed budget step
    private fun encodeWithinBudget(
        bitmap: Bitmap,
        rawMediaType: String,
        targetDir: File,
    ): NormalizationOutcome {
        var current = bitmap
        val (format, ext) = formatOf(rawMediaType)
        val scales =
            generateSequence(1.0) {
                (it * 0.75).takeIf { s -> s * min(bitmap.width, bitmap.height) >= MIN_LONG_EDGE_PX }
            }
        outer@ for (scale in scales) {
            for (quality in QUALITY_LADDER) {
                val out = File(targetDir, "normalized.$ext")
                val written =
                    try {
                        FileOutputStream(out).use { sink -> current.compress(format, quality, sink) }
                    } catch (e: Exception) {
                        out.delete()
                        break
                    }
                if (written && out.length() <= VisionLimits.MAX_NORMALIZED_RAW_BYTES) {
                    val sha = AtomicFileWriter.sha256Hex(out.toPath())
                    return NormalizationOutcome.Ok(
                        NormalizedImage(
                            file = out.toPath(),
                            mediaType = mimeTypeOf(format),
                            sizeBytes = out.length(),
                            sha256 = sha,
                            width = current.width,
                            height = current.height,
                        ),
                    )
                }
                out.delete()
            }
            // The quality ladder did not fit at this scale: downscale once and retry.
            val w = (current.width * scale).toInt().coerceAtLeast(MIN_LONG_EDGE_PX)
            val h = (current.height * scale).toInt().coerceAtLeast(MIN_LONG_EDGE_PX)
            if (w == current.width && h == current.height) break@outer
            val next = Bitmap.createScaledBitmap(current, w, h, true)
            if (next !== current) current.recycle()
            current = next
        }
        return NormalizationOutcome.Failed(
            NormalizationCode.BUDGET_EXCEEDED,
            "re-encoded output exceeds ${VisionLimits.MAX_NORMALIZED_RAW_BYTES} bytes",
        )
    }

    private fun formatOf(rawMediaType: String): Pair<Bitmap.CompressFormat, String> =
        when (rawMediaType) {
            "image/png" -> Bitmap.CompressFormat.PNG to "png"

            "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY to "webp"

            "image/gif" -> Bitmap.CompressFormat.PNG to "png"

            // first frame, palette-safe
            else -> Bitmap.CompressFormat.JPEG to "jpg"
        }

    private fun mimeTypeOf(format: Bitmap.CompressFormat): String =
        when (format) {
            Bitmap.CompressFormat.PNG -> "image/png"
            Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS -> "image/webp"
            else -> "image/jpeg"
        }
}
